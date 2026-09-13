package com.wallet.web;

import com.wallet.domain.Transfer;
import com.wallet.dto.TransferDetailResponse;
import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final WalletService walletService;

    public TransferController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Transfer record created. Check the response body's status field "
                            + "(SUCCESS or DECLINED_INSUFFICIENT_FUNDS) for the actual outcome - "
                            + "201 means the request was processed, not that money moved."),
            @ApiResponse(responseCode = "400",
                    description = "idempotency_key missing, from/to missing, from equals to, or amount_paise not positive"),
            @ApiResponse(responseCode = "404", description = "from or to wallet does not exist"),
            @ApiResponse(responseCode = "409", description = "idempotency_key reused with a different from/to/amount payload")
    })
    public ResponseEntity<TransferResponse> createTransfer(@RequestBody TransferRequest request) {
        long amountPaise = request.amountPaise() == null ? 0 : request.amountPaise();
        Transfer transfer;
        try {
            transfer = walletService.executeTransfer(
                    request.idempotencyKey(), request.from(), request.to(), amountPaise);
        } catch (DataIntegrityViolationException e) {
            // executeTransfer's transaction already rolled back in full. This call
            // opens its own transaction to read the committed winner.
            transfer = walletService.findExistingTransferOrThrow(
                    request.idempotencyKey(), request.from(), request.to(), amountPaise);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new TransferResponse(transfer.getId(), transfer.getIdempotencyKey(), transfer.getStatus()));
    }

    @GetMapping("/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transfer found"),
            @ApiResponse(responseCode = "404", description = "No transfer exists with this id")
    })
    public TransferDetailResponse getTransfer(@PathVariable UUID id) {
        Transfer transfer = walletService.getTransferOrThrow(id);
        return new TransferDetailResponse(transfer.getId(), transfer.getIdempotencyKey(),
                transfer.getFromWalletId(), transfer.getToWalletId(), transfer.getAmountPaise(),
                transfer.getStatus(), transfer.getCreatedAt());
    }
}
