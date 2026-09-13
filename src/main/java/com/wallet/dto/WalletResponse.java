package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record WalletResponse(
        UUID id,
        @JsonProperty("user_id") String userId,
        @JsonProperty("balance_paise") long balancePaise) {
}
