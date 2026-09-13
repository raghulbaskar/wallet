package com.wallet.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wallet.domain.Transfer;
import com.wallet.domain.Wallet;
import com.wallet.repository.TransferRepository;
import com.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final TransferRepository transferRepository;
    private final Counter transfersCreatedCounter;
    private final Counter transfersDeclinedCounter;
    private final Counter idempotentReplaysCounter;

    // Transfer rows are write-once (status is set exactly once, never updated
    // afterward), so a cached copy can never go stale - no invalidation needed.
    private final Cache<UUID, Transfer> transferCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build();

    public WalletService(WalletRepository walletRepository,
                          TransferRepository transferRepository,
                          MeterRegistry meterRegistry) {
        this.walletRepository = walletRepository;
        this.transferRepository = transferRepository;
        this.transfersCreatedCounter = Counter.builder("domain.transfers.succeeded.total").register(meterRegistry);
        this.transfersDeclinedCounter = Counter.builder("domain.transfers.declined.total").register(meterRegistry);
        this.idempotentReplaysCounter = Counter.builder("domain.transfers.idempotent_replays.total").register(meterRegistry);
    }

    @Transactional
    public Wallet getOrCreateWallet(String userId, long initialBalancePaise) {
        Wallet existing = walletRepository.findByUserId(userId).orElse(null);
        if (existing != null) {
            return existing;
        }
        if (initialBalancePaise < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "initial_balance_paise cannot be negative");
        }
        // ON CONFLICT DO NOTHING never raises a unique-violation, so there is no
        // aborted-transaction hazard here even under many concurrent callers.
        walletRepository.insertIfAbsent(UUID.randomUUID(), userId, initialBalancePaise);
        return walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("Race resolution failed for user: " + userId));
    }

    @Transactional(readOnly = true)
    public Wallet getWalletOrThrow(UUID id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + id));
    }

    public String computeRequestHash(UUID fromId, UUID toId, long amountPaise) {
        return sha256(fromId + ":" + toId + ":" + amountPaise);
    }

    @Transactional
    public Transfer executeTransfer(String idempotencyKey, UUID fromId, UUID toId, long amountPaise) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "idempotency_key is required");
        }
        if (fromId == null || toId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from and to wallet ids are required");
        }
        if (fromId.equals(toId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot transfer to the same wallet");
        }
        if (amountPaise <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount_paise must be positive");
        }
        String requestHash = computeRequestHash(fromId, toId, amountPaise);

        // Fast path for sequential retries after the winner has already committed.
        // Not the correctness mechanism by itself - see below.
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return requireMatchingPayload(existing, requestHash);
        }

        // Deterministic lock ordering (lower UUID first) makes circular waits impossible.
        // Locking both rows in one query preserves that same order (LockRows locks in
        // the sequence rows emerge from the ORDER BY beneath it) in a single round trip.
        UUID firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
        UUID secondId = fromId.compareTo(toId) < 0 ? toId : fromId;

        Map<UUID, Wallet> locked = walletRepository.findByIdsForUpdate(firstId, secondId)
                .stream().collect(Collectors.toMap(Wallet::getId, Function.identity()));
        Wallet from = locked.get(fromId);
        Wallet to = locked.get(toId);
        if (from == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + fromId);
        }
        if (to == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Wallet not found: " + toId);
        }

        Transfer.Status status;
        if (from.getBalancePaise() < amountPaise) {
            status = Transfer.Status.DECLINED_INSUFFICIENT_FUNDS;
            transfersDeclinedCounter.increment();
            log.warn("event=transfer_declined reason=insufficient_funds from_wallet={} amount={} current_balance={}",
                    from.getId(), amountPaise, from.getBalancePaise());
        } else {
            // Native update against the already-locked rows - one round trip instead of
            // two. Deliberately not touching from/to via JPA setters: that would make
            // Hibernate's own dirty-checking flush issue a second, duplicate UPDATE.
            walletRepository.applyTransfer(fromId, toId, amountPaise);
            long debitedBalance = from.getBalancePaise() - amountPaise;
            long creditedBalance = to.getBalancePaise() + amountPaise;
            status = Transfer.Status.SUCCESS;
            transfersCreatedCounter.increment();
            log.info("event=transfer_succeeded from_wallet={} to_wallet={} amount={} "
                            + "debited_balance={} credited_balance={}",
                    from.getId(), to.getId(), amountPaise, debitedBalance, creditedBalance);
        }

        Transfer transfer = new Transfer(UUID.randomUUID(), idempotencyKey, fromId, toId,
                amountPaise, status, requestHash);

        // No try/catch here. If a concurrent request already won this idempotency_key,
        // this insert throws DataIntegrityViolationException and Spring rolls the WHOLE
        // transaction back - the debit and credit above included - atomically. Recovery
        // happens in a fresh transaction instead, from the controller.
        Transfer saved = transferRepository.saveAndFlush(transfer);
        transferCache.put(saved.getId(), saved);
        return saved;
    }

    private Transfer requireMatchingPayload(Transfer existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with a different payload");
        }
        idempotentReplaysCounter.increment();
        log.info("event=idempotent_replay_hit idempotency_key={} transfer_id={}",
                existing.getIdempotencyKey(), existing.getId());
        transferCache.put(existing.getId(), existing);
        return existing;
    }

    @Transactional(readOnly = true)
    public Transfer findExistingTransferOrThrow(String idempotencyKey, UUID fromId, UUID toId, long amountPaise) {
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Concurrent transfer resolution failed: " + idempotencyKey));
        return requireMatchingPayload(existing, computeRequestHash(fromId, toId, amountPaise));
    }

    // Deliberately not @Transactional: Transfer rows are write-once (see transferCache
    // above), so a cache hit here returns without ever touching the database - opening
    // a transaction just for that would be pointless overhead. On a miss,
    // transferRepository.findById already runs inside its own default transaction.
    public Transfer getTransferOrThrow(UUID id) {
        Transfer cached = transferCache.getIfPresent(id);
        if (cached != null) {
            return cached;
        }
        Transfer transfer = transferRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transfer not found: " + id));
        transferCache.put(id, transfer);
        return transfer;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
