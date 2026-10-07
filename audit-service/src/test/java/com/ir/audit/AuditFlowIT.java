package com.ir.audit;

import com.ir.audit.store.AuditRecordStore;
import com.ir.common.event.AuditEvent;
import com.ir.common.event.Topics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Kafka → listener → DynamoDB-local; duplicates are absorbed; both query paths work. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AuditFlowIT {

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    @Container
    static GenericContainer<?> dynamo = new GenericContainer<>("amazon/dynamodb-local:2.6.1").withExposedPorts(8000);

    @DynamicPropertySource
    static void dynamoProps(DynamicPropertyRegistry r) {
        r.add("ir.audit.endpoint", () -> "http://" + dynamo.getHost() + ":" + dynamo.getMappedPort(8000));
        r.add("ir.audit.create-table", () -> "true");
    }

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JsonMapper json;

    @Autowired
    AuditRecordStore store;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void auditEventsLandOnceAndAreQueryableByEntityAndActor() throws Exception {
        String caseId = UUID.randomUUID().toString();
        AuditEvent triaged = new AuditEvent(UUID.randomUUID().toString(), caseId, "CASE", "CASE_TRIAGED", "OK",
                "op-it", "case-service", Map.of("method", "CaseService.triage(..)"), Instant.now());
        AuditEvent closed = new AuditEvent(UUID.randomUUID().toString(), caseId, "CASE", "CASE_CLOSED", "OK",
                "op-it", "case-service", Map.of(), Instant.now().plusMillis(5));

        String payload = json.writeValueAsString(triaged);
        kafkaTemplate.send(Topics.AUDIT_EVENTS, caseId, payload);
        kafkaTemplate.send(Topics.AUDIT_EVENTS, caseId, payload); // redelivery
        kafkaTemplate.send(Topics.AUDIT_EVENTS, caseId, json.writeValueAsString(closed));

        Instant from = Instant.now().minusSeconds(60);
        Instant to = Instant.now().plusSeconds(60);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(store.byEntity(caseId, from, to, 50, null).items())
                        .extracting(AuditRecordStore.AuditRecord::action)
                        .containsExactly("CASE_CLOSED", "CASE_TRIAGED")); // newest first, no duplicate

        assertThat(store.byActor("op-it", from, to, 50, null).items()).hasSizeGreaterThanOrEqualTo(2);

        var firstPage = store.byEntity(caseId, from, to, 1, null);
        assertThat(firstPage.nextToken()).isNotNull();
        assertThat(store.byEntity(caseId, from, to, 1, firstPage.nextToken()).items())
                .extracting(AuditRecordStore.AuditRecord::action).containsExactly("CASE_TRIAGED");
    }
}
