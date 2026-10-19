package com.ainexus.gateway.auth;

import com.ainexus.gateway.config.GatewayProperties;
import com.ainexus.gateway.entity.Account;
import com.ainexus.gateway.mapper.AccountMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/** Signed, expiring console session tokens; account state is checked on every API request. */
@Service
@RequiredArgsConstructor
public class ConsoleSessionService {
    private static final long EXPIRES_SECONDS = 12 * 60 * 60;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;
    private final AccountMapper accountMapper;

    public String issue(Account account) {
        try {
            long expiresAt = Instant.now().getEpochSecond() + EXPIRES_SECONDS;
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(
                    Map.of("id", account.getId(), "role", account.getRole(), "exp", expiresAt)));
            return payload + "." + signature(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to issue console session", e);
        }
    }

    public SessionAccount verify(String token) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2 || !MessageDigest.isEqual(signature(parts[0]).getBytes(StandardCharsets.UTF_8),
                    parts[1].getBytes(StandardCharsets.UTF_8))) return null;
            @SuppressWarnings("unchecked") Map<String, Object> payload = objectMapper.readValue(
                    Base64.getUrlDecoder().decode(parts[0]), Map.class);
            long expiresAt = ((Number) payload.get("exp")).longValue();
            if (expiresAt < Instant.now().getEpochSecond()) return null;
            Long id = ((Number) payload.get("id")).longValue();
            Account account = accountMapper.selectById(id);
            if (account == null || account.getStatus() == null || account.getStatus() != 1) return null;
            return new SessionAccount(account.getId(), account.getUsername(), account.getRole());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getConsole().getSessionSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    public record SessionAccount(Long id, String username, String role) { }
}
