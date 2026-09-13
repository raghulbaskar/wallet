package com.wallet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateWalletRequest(@JsonProperty("initial_balance_paise") Long initialBalancePaise) {
}
