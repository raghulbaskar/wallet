package com.wallet.web;

import com.wallet.domain.Wallet;
import com.wallet.dto.CreateWalletRequest;
import com.wallet.dto.WalletResponse;
import com.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Wallet created, or the caller's existing wallet returned unchanged"),
            @ApiResponse(responseCode = "400", description = "initial_balance_paise is negative"),
            @ApiResponse(responseCode = "401", description = "Missing or empty bearer token")
    })
    public ResponseEntity<WalletResponse> createWallet(
            @RequestHeader("Authorization") String authorization,
            @RequestBody(required = false) CreateWalletRequest request) {
        String userId = bearerToken(authorization);
        long initialBalancePaise = (request == null || request.initialBalancePaise() == null)
                ? 0L : request.initialBalancePaise();
        Wallet wallet = walletService.getOrCreateWallet(userId, initialBalancePaise);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(wallet));
    }

    @GetMapping("/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Wallet found"),
            @ApiResponse(responseCode = "404", description = "No wallet exists with this id")
    })
    public WalletResponse getWallet(@PathVariable UUID id) {
        return toResponse(walletService.getWalletOrThrow(id));
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        return token;
    }

    private static WalletResponse toResponse(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalancePaise());
    }
}
