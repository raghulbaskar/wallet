package com.wallet.repository;

import com.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(String userId);

    @Modifying
    @Query(value = "INSERT INTO wallets (id, user_id, balance_paise) "
            + "VALUES (:id, :userId, :balance) ON CONFLICT (user_id) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") String userId, @Param("balance") long balance);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

    // firstId must be < secondId (caller's deterministic lock order). ORDER BY
    // preserves that same acquisition order in a single round trip - Postgres's
    // LockRows node locks rows in the order they emerge from the Sort beneath it.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id IN (:firstId, :secondId) ORDER BY w.id ASC")
    List<Wallet> findByIdsForUpdate(@Param("firstId") UUID firstId, @Param("secondId") UUID secondId);

    // Runs against rows already locked by findByIdsForUpdate in this same transaction -
    // one round trip instead of two separate UPDATEs.
    @Modifying
    @Query(value = "UPDATE wallets SET balance_paise = CASE id "
            + "WHEN :fromId THEN balance_paise - :amount "
            + "WHEN :toId THEN balance_paise + :amount END "
            + "WHERE id IN (:fromId, :toId)",
            nativeQuery = true)
    int applyTransfer(@Param("fromId") UUID fromId, @Param("toId") UUID toId, @Param("amount") long amount);
}
