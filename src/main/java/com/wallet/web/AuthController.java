package com.wallet.web;

import com.wallet.dto.AuthCredentials;
import com.wallet.dto.AuthResponse;
import com.wallet.service.AuthService;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/signup")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Account created"),
            @ApiResponse(responseCode = "400", description = "Email and an 8+ character password are required"),
            @ApiResponse(responseCode = "409", description = "Account already exists")
    })
    public AuthResponse signup(@RequestBody AuthCredentials credentials) {
        return authService.signup(credentials);
    }

    @PostMapping("/auth/login")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login succeeded"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials")
    })
    public AuthResponse login(@RequestBody AuthCredentials credentials) {
        return authService.login(credentials);
    }
}
