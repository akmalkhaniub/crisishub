package com.ir.cases;

import com.ir.cases.repository.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full lifecycle against real MySQL + Kafka: report → triage → assign → resolve → close, then
 * verify the timeline, the outbox drained to {@code case.events}, and one audit event per action.
 * Skipped automatically when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {"ir.outbox.poll-ms=200", "ir.sla.scan-ms=600000"})
class CaseLifecycleIT {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.1.0");

    // Responder One from the Flyway seed (matches keycloak/realm-export.json user resp1).
    static final String RESP1_SUB = "00000000-0000-0000-0000-000000000004";

    @Autowired
    MockMvc mvc;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Test
    void reportToCloseProducesTimelineDomainEventsAndAuditTrail() throws Exception {
        String body = mvc.perform(post("/api/cases").with(user("member-it", "member"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"MEDICAL\",\"title\":\"Hiker injured\",\"lat\":46.5,\"lng\":7.9}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();

        mvc.perform(post("/api/cases/{id}/triage", id).with(user("op-it", "operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"severity\":\"HIGH\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/assign", id).with(user("op-it", "operator"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/resolve", id).with(user(RESP1_SUB, "responder"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"evacuated\"}")).andExpect(status().isOk());
        mvc.perform(post("/api/cases/{id}/close", id).with(user("op-it", "operator"))).andExpect(status().isOk());

        // another member can't see it (404, not 403)
        mvc.perform(get("/api/cases/{id}", id).with(user("someone-else", "member"))).andExpect(status().isNotFound());

        JsonNode timeline = json.readTree(mvc.perform(get("/api/cases/{id}/timeline", id).with(user("member-it", "member")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(timeline.findValuesAsString("eventType"))
                .containsExactly("REPORTED", "TRIAGED", "ASSIGNED", "RESOLVED", "CLOSED");

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(outbox.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).isEmpty());

        List<String> audited = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of("audit.events"));
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode e = json.readTree(r.value());
                    if (id.equals(e.get("entityId").asString())) {
                        audited.add(e.get("action").asString() + ":" + e.get("outcome").asString());
                    }
                }
                assertThat(audited).containsExactlyInAnyOrder("CASE_REPORTED:OK", "CASE_TRIAGED:OK",
                        "CASE_ASSIGNED:OK", "CASE_RESOLVED:OK", "CASE_CLOSED:OK");
            });
        }
    }

    private static RequestPostProcessor user(String sub, String role) {
        return jwt().jwt(j -> j.subject(sub).claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static KafkaConsumer<String, String> consumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + System.nanoTime());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(p);
    }
}
