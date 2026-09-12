package com.wallet.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class TokenService {

    private static final long TTL_SECONDS = 60 * 60 * 24;
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public TokenService(@Value("${auth.token-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String issue(String userId) {
        String payload = userId + "." + Instant.now().getEpochSecond();
        return payload + "." + sign(payload);
    }

    public String verify(String token) {
        try {
            int lastDot = token.lastIndexOf('.');
            String payload = token.substring(0, lastDot);
            String signature = token.substring(lastDot + 1);
            if (!sign(payload).equals(signature)) {
                return null;
            }
            String[] parts = payload.split("\\.", 2);
            long issuedAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() - issuedAt > TTL_SECONDS) {
                return null;
            }
            return parts[0];
        } catch (Exception e) {
            return null;
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] signature = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
