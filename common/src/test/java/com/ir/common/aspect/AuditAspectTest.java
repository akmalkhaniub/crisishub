package com.ir.common.aspect;

import com.ir.common.event.AuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditAspectTest {

    private final List<AuditEvent> published = new ArrayList<>();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishesOkEventWithActorActionAndEntityIdFromNamedParam() {
        authenticateAs("user-sub-1");
        UUID id = UUID.randomUUID();

        proxy(published::add).triage(id, "HIGH");

        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.outcome()).isEqualTo(AuditEvent.OK);
            assertThat(e.actor()).isEqualTo("user-sub-1");
            assertThat(e.action()).isEqualTo("CASE_TRIAGED");
            assertThat(e.entityId()).isEqualTo(id.toString());
            assertThat(e.serviceName()).isEqualTo("test-service");
            assertThat(e.details()).containsKeys("method", "durationMs").doesNotContainValue("HIGH");
        });
    }

    @Test
    void usesReturnValueIdForCreations() {
        authenticateAs("member-1");

        Created created = proxy(published::add).create("title");

        assertThat(published).singleElement()
                .extracting(AuditEvent::entityId).isEqualTo(created.auditEntityId());
    }

    @Test
    void actorIsSystemWithoutAuthentication() {
        proxy(published::add).triage(UUID.randomUUID(), "LOW");

        assertThat(published).singleElement().extracting(AuditEvent::actor).isEqualTo("system");
    }

    @Test
    void publishesFailedEventAndRethrows() {
        authenticateAs("op-1");
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> proxy(published::add).fail(id)).isInstanceOf(IllegalStateException.class);

        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.outcome()).isEqualTo(AuditEvent.FAILED);
            assertThat(e.entityId()).isEqualTo(id.toString());
            assertThat(e.details()).containsEntry("error", "IllegalStateException");
        });
    }

    @Test
    void publisherFailureDoesNotFailBusinessCall() {
        Target target = proxy(e -> {
            throw new RuntimeException("kafka down");
        });

        assertThat(target.triage(UUID.randomUUID(), "LOW")).isEqualTo("ok");
    }

    @Test
    void unannotatedMethodsAreNotAudited() {
        proxy(published::add).read();

        assertThat(published).isEmpty();
    }

    private static void authenticateAs(String sub) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(sub, null, "ROLE_x"));
    }

    private static Target proxy(AuditEventPublisher publisher) {
        AspectJProxyFactory factory = new AspectJProxyFactory(new Target());
        factory.addAspect(new AuditAspect(publisher, "test-service"));
        return factory.getProxy();
    }

    record Created(String id) implements AuditIdentifiable {
        @Override
        public String auditEntityId() {
            return id;
        }
    }

    static class Target {
        @Audited(action = "CASE_TRIAGED", entityIdParam = "caseId")
        public String triage(UUID caseId, String severity) {
            return "ok";
        }

        @Audited(action = "CASE_CREATED")
        public Created create(String title) {
            return new Created(UUID.randomUUID().toString());
        }

        @Audited(action = "CASE_CLOSED", entityIdParam = "caseId")
        public void fail(UUID caseId) {
            throw new IllegalStateException("boom");
        }

        public void read() {
        }
    }
}
