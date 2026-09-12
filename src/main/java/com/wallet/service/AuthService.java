package com.wallet.service;

import com.wallet.domain.User;
import com.wallet.dto.AuthCredentials;
import com.wallet.dto.AuthResponse;
import com.wallet.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(UserRepository userRepository, TokenService tokenService) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
    }

    @Transactional
    public AuthResponse signup(AuthCredentials credentials) {
        if (credentials.email() == null || credentials.password() == null || credentials.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email and an 8+ character password are required");
        }
        String email = credentials.email().trim().toLowerCase();
        User user = new User(email, passwordEncoder.encode(credentials.password()));
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            log.warn("event=signup_failed reason=email_exists email={}", maskEmail(email));
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account already exists");
        }
        log.info("event=signup_success user_id={}", user.getId());
        return new AuthResponse(user.getId().toString(), user.getEmail(), tokenService.issue(user.getId().toString()));
    }

    public AuthResponse login(AuthCredentials credentials) {
        String email = credentials.email().trim().toLowerCase();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || !passwordEncoder.matches(credentials.password(), user.getPasswordHash())) {
            log.warn("event=login_failed reason=invalid_credentials email={}", maskEmail(email));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        log.info("event=login_success user_id={}", user.getId());
        return new AuthResponse(user.getId().toString(), user.getEmail(), tokenService.issue(user.getId().toString()));
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return "*".repeat(Math.max(email.length(), 1));
        }
        return email.charAt(0) + "*".repeat(at - 1) + email.substring(at);
    }
}
