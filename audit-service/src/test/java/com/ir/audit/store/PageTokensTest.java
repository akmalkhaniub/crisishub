package com.ir.audit.store;

import com.ir.common.error.ApiException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageTokensTest {

    @Test
    void roundTripsLastEvaluatedKey() {
        Map<String, AttributeValue> key = Map.of(
                "entityId", AttributeValue.fromS("case-1"),
                "timestampId", AttributeValue.fromS("2026-10-07T12:00:00.000000Z#e1"),
                "actor", AttributeValue.fromS("op-1"));

        assertThat(PageTokens.decode(PageTokens.encode(key))).isEqualTo(key);
    }

    @Test
    void emptyKeyMeansNoMorePages() {
        assertThat(PageTokens.encode(Map.of())).isNull();
        assertThat(PageTokens.decode(null)).isNull();
    }

    @Test
    void garbageTokenIs400() {
        assertThatThrownBy(() -> PageTokens.decode("%%%"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("invalid-token"));
    }
}
