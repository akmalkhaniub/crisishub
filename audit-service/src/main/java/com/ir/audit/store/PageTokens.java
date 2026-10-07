package com.ir.audit.store;

import com.ir.common.error.ApiException;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Opaque {@code nextToken} ↔ DynamoDB {@code LastEvaluatedKey} (all key attributes are strings). */
public final class PageTokens {

    private PageTokens() {
    }

    public static String encode(Map<String, AttributeValue> lastEvaluatedKey) {
        if (lastEvaluatedKey == null || lastEvaluatedKey.isEmpty()) {
            return null;
        }
        String raw = lastEvaluatedKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "\u0000" + e.getValue().s())
                .collect(Collectors.joining("\u0001"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, AttributeValue> decode(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            Map<String, AttributeValue> key = new LinkedHashMap<>();
            for (String pair : raw.split("\u0001")) {
                String[] kv = pair.split("\u0000", 2);
                key.put(kv[0], AttributeValue.fromS(kv[1]));
            }
            return key;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid-token", "Malformed nextToken");
        }
    }
}
