package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record TransferDetailResponse(
        UUID id,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("from_wallet_id") UUID fromWalletId,
        @JsonProperty("to_wallet_id") UUID toWalletId,
        @JsonProperty("amount_paise") long amountPaise,
        String status,
        @JsonProperty("created_at") Instant createdAt) {
}
