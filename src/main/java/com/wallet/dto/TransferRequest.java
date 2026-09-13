package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record TransferRequest(
        @JsonProperty("idempotency_key") String idempotencyKey,
        UUID from,
        UUID to,
        @JsonProperty("amount_paise") Long amountPaise) {
}
