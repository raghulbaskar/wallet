package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record TransferResponse(
        UUID id,
        @JsonProperty("idempotency_key") String idempotencyKey,
        String status) {
}
