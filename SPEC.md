# Full Spec-Driven Spec — CrisisHub: Incident Response & Case Management System
### Distributed Systems Architecture · Spring Boot 4.x / Java 21 / Kafka / DynamoDB

> HOW TO USE: build in the PHASE order at the end. This system demonstrates enterprise
> microservice architecture, Keycloak OIDC, Spring AOP, DynamoDB immutable audit logs,
> transactional outbox pattern, and finite state machines.

---

## 0. SYSTEM SUMMARY

End-to-end incident/case management: report → triage → assign responder → track
lifecycle → resolve → close, with real-time updates, immutable audit, and Keycloak
security (OIDC + SAML federation). Mirrors enterprise-grade crisis and emergency response workflows.

**Services (ports):**
- `case-service` (8081) — case lifecycle (state machine)
- `responder-service` (8082) — responder registry + assignment
- `notification-service` (8083) — WebSocket + email
- `audit-service` (8084) — immutable audit log (DynamoDB)
- `reporting-service` (8085) — SLA metrics + S3 export
- `api-gateway` (8080) — Spring Cloud Gateway + auth
- `portal` (3000) — React 18 + TypeScript + Vite (decided; Angular is out of scope)

**Infra (docker-compose):** MySQL, Redis, Kafka 4.x in **KRaft mode** (no ZooKeeper),
Keycloak (host port **8180**), DynamoDB-local (or LocalStack), (optional) LocalStack for
S3/SES, MailHog/Mailpit for viewing emails locally. Kafka is the decided broker
(RabbitMQ not used — keep one path).

**Host port map:** gateway 8080 · services 8081–8085 · Keycloak 8180 · MySQL 3306 ·
Redis 6379 · Kafka 9092 · DynamoDB-local 8000 · LocalStack 4566 · Mailpit 8025 · portal 3000.

**Identity convention:** `memberId`, `actor`, responder `userId` = Keycloak `sub`
claim (stable UUID), never the username. Display names come from `preferred_username`.

---

## 1. TECH STACK (pin versions)
- Java 21 (LTS; min 17, compatible up to Java 26), Spring Boot 4.1.x, Spring Framework 7.0.x, Spring Cloud 2025.x

> VERSION NOTE (verified current as of Oct 2026): Spring Boot 4.1.x is current
> (requires Spring Framework 7.0+, Java 17 min / 21 LTS recommended). Spring Boot 3.x
> is EOL for OSS support. These versions move fast — re-check latest stable on
> start.spring.io or the Spring release notes before you scaffold, and let
> start.spring.io pick matching dependency versions rather than pinning by hand.
- Spring Web, Data JPA, Security (OAuth2 Resource Server), Spring Kafka, WebSocket,
  Cloud Gateway, AOP/AspectJ, enum-based FSM (decided — see §4), Resilience4j, ShedLock
  > Generate the POM from start.spring.io; don't hand-type starter names (Boot 4
  > renamed several, e.g. `spring-boot-starter-aspectj`; Spring Kafka 4 uses Jackson 3).
- MySQL 8, Redis 7, Kafka 4.x KRaft, Keycloak 26.x, DynamoDB (local/LocalStack)
- AWS SDK v2 (DynamoDB Enhanced Client, S3 + S3Presigner, SES v2)
- Flyway migrations (`ddl-auto=validate`)
- Testing: JUnit 5, Mockito, Testcontainers (+ testcontainers-keycloak), Spring Security Test, Awaitility
- Compiler flag `-parameters` enabled in parent POM (needed by AuditAspect, §8)
- Maven multi-module · Frontend React 18 + TS + Vite + keycloak-js · Docker/K8s

---

## 2. MAVEN MULTI-MODULE STRUCTURE
```
incident-response/
├── pom.xml (parent)
├── common/           (events, DTOs, @Audited, role constants, error model)
├── case-service/
├── responder-service/
├── notification-service/
├── audit-service/
├── reporting-service/
├── api-gateway/
├── docker-compose.yml
├── keycloak/realm-export.json
└── k8s/
```
Per-service packages: `controller · service · repository · entity · dto · config ·
aspect · statemachine · exception`.

---

## 3. DATA MODELS

### case-service — Case (MySQL `case_record`)
```
Case {
  UUID id (PK)
  String caseNumber (unique, e.g. CR-2026-000123)
  CaseType type            // MEDICAL, SECURITY, EVACUATION, TRAVEL_ASSIST
  CaseSeverity reportedSeverity // member's self-assessment at report time (nullable)
  CaseSeverity severity    // LOW, MEDIUM, HIGH, CRITICAL — authoritative, set at triage
  CaseStatus status        // REPORTED, TRIAGED, IN_PROGRESS, RESOLVED, CLOSED, CANCELLED
  boolean assignmentPending // true when triaged but responder-service was unavailable
  String memberId          // Keycloak sub of reporter
  String title
  String description
  Double lat, lng          // incident location (PII — never logged)
  String assignedResponderId  // nullable until assigned
  Instant reportedAt, triagedAt, assignedAt, resolvedAt, closedAt, cancelledAt
  Instant slaDueAt         // computed from severity at triage; NOT reset on reopen
  int reopenCount
  String cancelReason
  Long version             // @Version optimistic locking → concurrent edits get 409
  Instant createdAt, updatedAt
}
CaseEvent {  // append-only timeline within case-service (user-facing history)
  UUID id; UUID caseId; String eventType; String fromStatus; String toStatus;
  String detail; String actor; Instant at;
}
OutboxEvent { // transactional outbox (see §5)
  UUID id; String topic; String key; String payloadJson; Instant createdAt; Instant publishedAt;
}
```
Indexes: caseNumber(unique), status, memberId, assignedResponderId, slaDueAt.
`caseNumber` = `CR-{year}-{6-digit seq}` from a DB sequence table (safe across instances).
CaseEvent (operational timeline shown to users) and the DynamoDB AuditRecord (§3, the
compliance trail) are intentionally separate: different audiences, retention and access.

### responder-service — Responder (MySQL `responder`)
```
Responder {
  UUID id (PK)
  String userId                // Keycloak sub of the responder user (unique)
  String name
  Set<ResponderSkill> skills   // MEDICAL, SECURITY, LOGISTICS, EVACUATION
  Double baseLat, baseLng
  ResponderStatus status       // AVAILABLE, ON_CASE, OFFLINE
  Integer activeCaseCount
  Integer maxConcurrentCases   // default 2
  Long version                 // @Version — prevents double-booking races
}
ProcessedAssignment { String eventId (PK); Instant at; }  // consumer idempotency
```
Case type → required skill: MEDICAL→MEDICAL, SECURITY→SECURITY,
EVACUATION→EVACUATION, TRAVEL_ASSIST→LOGISTICS.

### audit-service — AuditRecord (DynamoDB table `audit_log`)
```
AuditRecord {
  String entityId (Partition Key)      // e.g. caseId
  String timestampId (Sort Key)        // `{event.at ISO-8601 UTC}#{event.eventId}` — both
                                       // set by the PRODUCER, so a redelivered event maps
                                       // to the same key (idempotent)
  String eventId
  String actor
  String action                        // CASE_CREATED, STATUS_CHANGED, ASSIGNED, ...
  String outcome                       // OK | FAILED
  String entityType
  Map<String,String> details           // before/after status, method, durationMs (no PII)
  String serviceName
  String traceId
}
```
GSI `actor-index` (PK=actor, SK=timestampId) for "what did user X do" queries.

**Immutability is enforced, not just promised:**
- Writes use `PutItem` with `ConditionExpression: attribute_not_exists(entityId)` →
  duplicates are a no-op (catch `ConditionalCheckFailedException`, treat as success).
- audit-service's IAM role allows only `PutItem`, `Query`, `GetItem`; an explicit
  **Deny** on `UpdateItem`, `DeleteItem`, `BatchWriteItem`(deletes), `DeleteTable`.
  (Document the policy in CloudFormation; DynamoDB-local ignores IAM — say so.)
- Point-in-time recovery enabled; stretch: stream to S3 with Object Lock (WORM).
- No update/delete endpoints exist in audit-service.

---

## 4. CASE STATE MACHINE (architecture showcase)

States: REPORTED → TRIAGED → IN_PROGRESS → RESOLVED → CLOSED, plus terminal CANCELLED.

| From | To | Action / endpoint | Who | Guards & side effects |
|---|---|---|---|---|
| REPORTED | TRIAGED | triage | operator | severity required; sets triagedAt, slaDueAt |
| REPORTED | CANCELLED | cancel | operator, or owning member | reason required (false alarm/duplicate) |
| TRIAGED | IN_PROGRESS | assign | operator | responder must exist, be AVAILABLE, have required skill and capacity; clears assignmentPending |
| TRIAGED | TRIAGED | assign (responder-service down) | operator | sets assignmentPending=true; 202 Accepted; retried by scheduler |
| TRIAGED | CANCELLED | cancel | operator | reason required |
| IN_PROGRESS | IN_PROGRESS | reassign | operator | releases old responder, assigns new; CaseEvent REASSIGNED |
| IN_PROGRESS | TRIAGED | decline | assigned responder | releases responder; assignedResponderId=null |
| IN_PROGRESS | RESOLVED | resolve | **the assigned responder** (or operator) | resolution note required; sets resolvedAt |
| RESOLVED | IN_PROGRESS | reopen | operator | same responder; resolvedAt=null; reopenCount++; slaDueAt unchanged (SLA clock is from report) |
| RESOLVED | CLOSED | close | operator | sets closedAt |

Everything else → **409 Conflict** (problem+json, `type=/errors/illegal-transition`).
CLOSED and CANCELLED are terminal. Triage cannot be skipped by any path (there is no
REPORTED→IN_PROGRESS edge); additionally a case whose `reportedSeverity` is CRITICAL
is flagged on the operator queue and its provisional SLA (15 min to triage) is
tracked from report time.

**Implementation (decided): enum FSM**, not Spring StateMachine (simpler, fully
testable, no framework state to persist):
```java
public final class CaseStateMachine {               // pure, no Spring deps
  private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = Map.of(
      REPORTED,    Set.of(TRIAGED, CANCELLED),
      TRIAGED,     Set.of(TRIAGED, IN_PROGRESS, CANCELLED),
      IN_PROGRESS, Set.of(IN_PROGRESS, TRIAGED, RESOLVED),
      RESOLVED,    Set.of(IN_PROGRESS, CLOSED),
      CLOSED,      Set.of(),
      CANCELLED,   Set.of());
  public static void check(CaseStatus from, CaseStatus to) {
    if (!ALLOWED.get(from).contains(to)) throw new IllegalTransitionException(from, to);
  }
}
```
Edge guards (who / preconditions) live in `CaseService`, one public method per action
(`triage`, `assign`, `reassign`, `decline`, `resolve`, `reopen`, `close`, `cancel`).
Each public method is `@Transactional` + `@Audited` and calls a **private, non-audited**
helper `applyTransition(case, target, actor)` that checks the FSM, mutates, writes a
CaseEvent and an OutboxEvent. Do NOT put `@Audited` on `applyTransition` — internal
`this.` calls bypass the Spring proxy, so the aspect would never run (self-invocation).

---

## 5. EVENT SCHEMAS (Kafka/RabbitMQ — in `common`)
Topics: `case.events`, `assignment.events`, `audit.events` (key = entity/case id).
Every event carries `String eventId` (UUID, producer-generated) and `String traceId`.
```
CaseDomainEvent { String eventId; String caseId; String caseNumber; String eventType;
                  String fromStatus; String status; String severity; String memberId;
                  String assignedResponderId; String actor; Instant at;
                  Instant slaDueAt; Map<String,String> data; }
AssignmentEvent { String eventId; String caseId; String responderId;
                  String action /* ASSIGNED|RELEASED */; Instant at; }
AuditEvent      { String eventId; String entityId; String entityType; String action;
                  String outcome; String actor; String serviceName;
                  Map<String,String> details; Instant at; }
```
(`notification.requests` dropped — notification-service derives notifications directly
from `case.events`; one fewer topic with no producer.)

**Topic ownership:**
| Topic | Producer | Consumers |
|---|---|---|
| `case.events` | case-service (outbox) | notification-service, reporting-service |
| `assignment.events` | case-service (outbox) on assign/reassign/decline/resolve/cancel | responder-service |
| `audit.events` | AuditAspect in every service | audit-service |

**Reliability:**
- case-service writes domain/assignment events to the **OutboxEvent table in the same
  DB transaction** as the state change; a `@Scheduled` relay (ShedLock) publishes
  unpublished rows to Kafka and sets `publishedAt`. No dual-write: a rollback emits
  nothing; a crash re-sends (consumers are idempotent by `eventId`).
- At-least-once delivery; every consumer dedupes by `eventId`. 3 retries then `<topic>.DLT`.

---

## 6. SERVICE RESPONSIBILITIES & REST APIs

### api-gateway (8080)
Routes `/api/cases/**`, `/api/responders/**`, `/api/audit/**`, `/api/reports/**`, `/ws/**`.
**Auth model (decided):** the portal (public PKCE client) holds the token and sends
`Authorization: Bearer`; the gateway is a **Resource Server that validates and forwards
the header** (no session, no `TokenRelay` filter). Downstream services re-validate.
CORS for `http://localhost:3000`; WebSocket passthrough; rate limiting (bonus,
`RequestRateLimiter` with Redis, keyed by `sub`).
All errors: RFC 9457 `application/problem+json` (shared model in `common`).

### case-service (8081)
```
POST  /api/cases                       (member)      report → REPORTED (memberId = token sub)
GET   /api/cases?status=&type=&page=   (scoped)      member: own only (filter forced in query);
                                                     responder: assigned to them; operator/admin/auditor: all
GET   /api/cases/{id}                  (scoped)      detail; out-of-scope → 404 (don't leak existence)
POST  /api/cases/{id}/triage           (operator)    body {severity} → TRIAGED
POST  /api/cases/{id}/assign           (operator)    body {responderId?} — omitted → assign-best → IN_PROGRESS;
                                                     responder-service down → 202, assignmentPending=true
POST  /api/cases/{id}/reassign         (operator)    body {responderId?}
POST  /api/cases/{id}/decline          (assigned responder) → TRIAGED
POST  /api/cases/{id}/resolve          (assigned responder | operator) body {note} → RESOLVED
POST  /api/cases/{id}/reopen           (operator)    → IN_PROGRESS
POST  /api/cases/{id}/close            (operator)    → CLOSED
POST  /api/cases/{id}/cancel           (operator | owning member while REPORTED) body {reason}
GET   /api/cases/{id}/timeline         (scoped)      CaseEvents
```
Mutations accept `If-Match: <version>` (ETag); stale version → 409/412.
Each public mutating method `@Transactional` + `@Audited`; transitions validated by
the FSM; events via outbox. Ownership checks live in the service layer (e.g.
`case.memberId.equals(jwt.sub)`), not only in `@PreAuthorize` role checks.

**Assignment flow (sync reserve + async confirm):**
1. case-service calls `POST /internal/responders/reserve` on responder-service
   (Resilience4j: timeout 2 s, retry 2× with backoff, circuit breaker 50% / 10 calls).
   responder-service picks best (or the requested) responder and atomically
   increments `activeCaseCount` with optimistic locking → returns responderId.
2. case-service transitions to IN_PROGRESS and writes `AssignmentEvent(ASSIGNED)` to
   the outbox (used for notifications/analytics; reservation is already applied, so
   responder-service treats ASSIGNED as a no-op confirm).
3. On decline/resolve/cancel/reassign: outbox `AssignmentEvent(RELEASED)` →
   responder-service decrements count (idempotent by eventId).
4. Breaker open / timeout → case stays TRIAGED, `assignmentPending=true`, 202 returned;
   a `@Scheduled` job (every 30 s, ShedLock) retries pending assignments.
5. Orphan protection: reservations not confirmed by an ASSIGNED event within 5 min are
   released by responder-service.

### responder-service (8082)
```
POST /api/responders                     (admin)               register (links Keycloak userId)
GET  /api/responders?skill=&status=      (operator)            list
GET  /api/responders/me                  (responder)           own profile + active cases count
POST /api/responders/{id}/availability   (that responder | admin) set AVAILABLE/OFFLINE
POST /internal/responders/reserve        (service: ir-case-service client, scope `responder.reserve`)
                                         body {caseId, requiredSkill, lat, lng, responderId?}
```
`/internal/**` is NOT routed by the gateway and requires a client-credentials token
from the `ir-case-service` confidential client. Matching: filter by skill + AVAILABLE +
`activeCaseCount < maxConcurrentCases`; score = haversine distance (km) + 25 km ×
activeCaseCount; lowest wins; tie → lowest id. No candidate → 409 `no-responder-available`.
Consumes `assignment.events` (idempotent via ProcessedAssignment). Status is derived:
ON_CASE when activeCaseCount > 0, unless manually OFFLINE.

### notification-service (8083)
- Consumes `case.events` → WebSocket updates + email via SES (Mailpit locally).
  Recipients: member on every status change of their case; assigned responder on
  assign/reopen; operators (`/topic/operators/queue`) on new REPORTED and SLA breach.
- STOMP endpoint `/ws`; token sent in the STOMP **CONNECT frame** (browsers can't set
  headers on the WS handshake), validated by a `ChannelInterceptor` → Principal = sub.
- **Subscription authorization** (otherwise any user can listen to any case):
  `/user/queue/notifications` — any authenticated user (per-user by Principal);
  `/topic/cases/{id}` — interceptor checks the subscriber may view that case
  (member owner, assigned responder, or operator/admin) via a cached call to case-service
  scope check; `/topic/operators/**` — operator/admin only.
- Redis for per-user unread counts (`notif:unread:{sub}`); `POST /api/notifications/read`.
- Emails contain case number + status + link only (no description/location PII).

### audit-service (8084)
- Consumes `audit.events` → conditional PutItem into DynamoDB (idempotent, §3).
```
GET /api/audit?entityId=&from=&to=&nextToken=   (auditor, admin)  by entity (PK query)
GET /api/audit?actor=&from=&to=&nextToken=      (auditor, admin)  by actor (GSI)
```
One of entityId/actor required (no table scans). Pagination via DynamoDB
`LastEvaluatedKey` encoded as opaque `nextToken`. Reading the audit log is itself
`@Audited(action="AUDIT_QUERIED")`.

### reporting-service (8085)
Data source: consumes `case.events` into its own read model (MySQL `case_report_view`:
caseId, type, severity, status, reportedAt, slaDueAt, resolvedAt, closedAt) — CQRS
projection, no reads from case-service's DB.
```
GET /api/reports/sla-summary                 (operator, admin)  open / breached / met by severity
GET /api/reports/cases?from=&to=&format=csv  (admin)            CSV → S3 `ir-reports/{uuid}.csv`,
                                                                returns presigned GET URL (15 min)
```
SLA breached = (resolvedAt ?: now) > slaDueAt. Exports exclude description and
lat/lng; S3 bucket private, SSE-S3, lifecycle delete after 7 days.
SLA breach scanner: lives in **case-service** (the owner of case state), `@Scheduled`
every minute (ShedLock): cases not RESOLVED/CLOSED/CANCELLED with `slaDueAt < now` and
no prior breach event → CaseEvent + outbox `CaseDomainEvent(SLA_BREACHED)` (once per
case). notification-service alerts operators; reporting-service updates its view.
(Lambda variant in §10.)

---

## 7. KEYCLOAK CONFIG (deep — the primary gap closer; realm-export.json committed)

- Realm: `incident-response`
- Clients:
  - `ir-portal` (public, standard flow + PKCE S256, direct grants OFF) — React app.
    Redirect URI `http://localhost:3000/*`, web origin `http://localhost:3000`,
    access token 5 min, refresh via keycloak-js. Audience mapper adds `ir-api`.
  - `ir-case-service` (confidential, **service accounts only**, client-credentials) —
    case-service → responder-service `/internal/**`; service-account role/scope
    `responder.reserve`.
  - `ir-api` (no flows enabled, used only as the audience) — replaces the old
    "bearer-only" client type, which the current Keycloak admin console no longer offers.
  - (no gateway client — gateway is a pure resource server, §6)
- Roles (realm): `member`, `operator`, `responder`, `auditor`, `admin`
  (`admin` is a composite including `operator` and `auditor`).
- Groups: `Operations` (operator), `Field` (responder), `Compliance` (auditor),
  `Members` (member) — roles assigned via groups, not directly to users.
- Seed users (demo-only passwords, documented in README): member1, member2 (to test
  ownership), op1, resp1, resp2, aud1, admin. resp1/resp2 have matching Responder rows
  seeded by Flyway with their Keycloak `sub` (fixed user ids in realm-export).
- Password policy, brute-force detection ON, SSL required = external (shows awareness).
- **SAML federation (bonus, strong talking point):** add a SAML 2.0 Identity Provider
  to the realm, backed by a local mock IdP — either a **second Keycloak realm
  `corp-idp` acting as SAML IdP** (zero extra containers) or a SimpleSAMLphp container.
  (samltest.id is no longer available — don't depend on it.) Configure: NameID
  persistent, attribute mapper `Role` → realm role `operator` for the `ops` SAML group,
  first-broker-login flow. Demo: "Sign in with Corp SSO" button on the Keycloak login
  page, and the portal receives a normal OIDC token — the point being apps stay
  OIDC-only while Keycloak brokers SAML.
- **Issuer consistency (most common local failure):** the browser reaches Keycloak at
  `http://localhost:8180`, containers at `http://keycloak:8080`; tokens carry the
  browser's issuer. Set `KC_HOSTNAME=http://localhost:8180` and
  `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`, and in every service:
  ```yaml
  spring.security.oauth2.resourceserver.jwt:
    issuer-uri: http://localhost:8180/realms/incident-response   # equals token `iss`
    jwk-set-uri: http://keycloak:8080/realms/incident-response/protocol/openid-connect/certs
  ```
  plus a custom `JwtDecoder` validator chain: issuer + `aud` contains `ir-api` + timestamps.
- `JwtAuthenticationConverter` (in `common`) maps `realm_access.roles` → `ROLE_*`
  authorities and `scope` → `SCOPE_*`; principal name = `sub`.
- Method security: `@EnableMethodSecurity`, `@PreAuthorize("hasRole('operator')")`,
  plus resource-level checks in the service layer (members only their own cases,
  responders only cases assigned to them). Out-of-scope reads return **404**, not 403.
- Role matrix (single source of truth; `SecurityIT` tests every cell):

| Action | member | responder | operator | auditor | admin |
|---|---|---|---|---|---|
| report case | ✓ | | | | |
| view case | own | assigned | all | all (read-only) | all |
| triage / assign / reassign / reopen / close | | | ✓ | | ✓ |
| cancel | own, while REPORTED | | ✓ | | ✓ |
| decline / resolve | | assigned | resolve ✓ | | ✓ |
| manage responders | | own availability | list | | ✓ |
| query audit | | | | ✓ | ✓ |
| SLA summary / CSV export | | | summary | | ✓ |

---

## 8. SPRING AOP (the audit aspect — primary gap closer)

Package `com.ir.common.aspect` (shipped as a Boot auto-configuration so every service
gets it by depending on `common`):
```java
@Retention(RUNTIME) @Target(METHOD)
public @interface Audited {
  String action();
  String entityType() default "CASE";
  String entityIdParam() default "";   // name of the method parameter holding the id
}

@Aspect @RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 1)  // OUTSIDE @Transactional → sees commit result
public class AuditAspect {
  private final AuditEventPublisher publisher;   // KafkaTemplate → audit.events
  private final String serviceName;              // from spring.application.name

  @Around("@annotation(audited)")                // lowercase binds the parameter
  public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
    String actor = currentActor();
    String entityId = extractEntityId(pjp, audited);
    long t0 = System.nanoTime();
    try {
      Object result = pjp.proceed();             // includes the commit
      publisher.publish(AuditEvent.of(audited, entityId, actor, serviceName, "OK",
          details(pjp, t0, null)));
      return result;
    } catch (Throwable ex) {
      publisher.publish(AuditEvent.of(audited, entityId, actor, serviceName, "FAILED",
          details(pjp, t0, ex)));
      throw ex;
    }
  }

  private String currentActor() {
    Authentication a = SecurityContextHolder.getContext().getAuthentication();
    if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return "system";
    return a.getName();                          // = JWT sub (converter sets principal name)
  }

  // Uses MethodSignature#getParameterNames → requires the `-parameters` compiler flag.
  // Fallback: first UUID/String arg; if none, "unknown" + a WARN log (never throw).
  private String extractEntityId(ProceedingJoinPoint pjp, Audited a) { ... }

  // {method, durationMs, error=exception class simple name}. Never raw args / PII.
  private Map<String,String> details(ProceedingJoinPoint pjp, long t0, Throwable ex) { ... }
}
```
Rules / design notes:
- **Ordering vs transactions:** the aspect wraps `@Transactional`, so `OK` is published
  only after commit; a rollback produces `FAILED`. (If you need *zero* gap between commit
  and audit, write AuditEvent to the same outbox table — mention as the production upgrade.)
- **Publisher must never break the business call:** publish is async with its own
  error handling; failure → ERROR log + metric `audit.publish.failures`, not an exception.
  (State the trade-off: availability over completeness; outbox removes it.)
- **Self-invocation:** only public methods invoked through the Spring proxy are audited
  — hence `@Audited` sits on `CaseService.triage/assign/...`, never on `applyTransition`.
- Responder status changes in responder-service and audit queries in audit-service
  use the same annotation.
- Optional second aspect: `@Timed`-style perf aspect → Micrometer timer.

Usage: `@Audited(action="CASE_TRIAGED", entityIdParam="caseId")`.
Talking points: why AOP for audit (cross-cutting, uniform, not scattered); how it reads
the authenticated principal; aspect ordering vs transactions; proxy limits
(self-invocation, final/private methods); how the event flows to an immutable DynamoDB store.

---

## 9. RESILIENCE & CACHING
- Resilience4j on case-service → responder-service: timeout 2 s, retry 2× (exp backoff,
  only on IO/5xx, never on 409), circuit breaker (50% failure over 10 calls, 30 s open).
- Redis: notification unread counts; case-view scope cache for WS subscription checks
  (TTL 60 s). Do **not** cache responder availability used for assignment — reservation
  must hit the DB with optimistic locking.
- Graceful degradation: responder-service down → case stays TRIAGED with
  `assignmentPending=true`, 202 returned, background retry (§6).
- Concurrency: `@Version` on Case and Responder; `OptimisticLockException` → 409.
- Kafka consumers: 3 retries then DLT; idempotent by `eventId`.
- Health: Actuator readiness includes DB, Kafka, Redis, DynamoDB; compose healthchecks.

---

## 10. AWS PIECES (LocalStack ok for dev)
- **DynamoDB**: audit_log table (immutable trail) + `actor-index` GSI, PITR on.
- **S3**: exported case/SLA reports; presigned URLs (15 min); private, SSE, 7-day lifecycle.
- **SES**: case notification emails (Mailpit/LocalStack locally; sandbox in AWS).
- **Lambda** (bonus): alternative SLA-breach scanner (EventBridge schedule) calling
  case-service's breach endpoint — compare with the in-service `@Scheduled` version.
- **IAM**: least-privilege role per service; audit-service deny Update/Delete (§3).
- **CloudFormation**: DynamoDB table + GSI, S3 bucket, IAM roles/policies, (ECS services).

---

## 11. TEST SPECS (TDD — write first)

### Unit
- `CaseStateMachineTest` — parameterized over ALL 6×6 status pairs: exactly the §4 table
  passes, everything else throws; CLOSED/CANCELLED terminal; no path skips TRIAGED.
- `CaseServiceTest` — guards: resolve by non-assigned responder → forbidden; reopen
  keeps slaDueAt and increments reopenCount; assign when breaker open → TRIAGED +
  assignmentPending; cancel by member only while REPORTED.
- `ResponderMatchingTest` — skill filter; capacity filter; nearer wins at equal load;
  load penalty can outweigh distance; deterministic tie-break; none → no-responder error.
- `SlaCalculatorTest` — slaDueAt by severity (define table: CRITICAL 1 h, HIGH 4 h,
  MEDIUM 24 h, LOW 72 h, measured from `reportedAt`).
- `AuditAspectTest` — use `AspectJProxyFactory` with a mock publisher: OK event with
  actor (=sub), action, entityId from named param; `system` with no auth; FAILED event
  + exception rethrown; publisher failure does NOT fail the business call.
- `AuditArchitectureTest` — ArchUnit rule: `@Audited` only on public methods of
  `@Service` classes (guards against self-invocation/private-method mistakes).

### Integration (Testcontainers)
- `CaseLifecycleIT` — full flow report→triage→assign→resolve→close; timeline + one audit event per action.
- `OutboxIT` — forced rollback after transition → no Kafka event; relay restart → no duplicate effects.
- `ConcurrentAssignIT` — two parallel assigns on the same case → one succeeds, one 409;
  two cases competing for one responder at capacity 1 → only one gets it.
- `AuditFlowIT` — audit events land in DynamoDB-local; same event delivered twice →
  one record; query by entityId and by actor returns them, paginated.
- `ResponderOutageIT` — responder-service stopped → 202 + pending; restarted → retry job assigns.
- `SecurityIT` — every cell of the §7 role matrix against a real Keycloak container
  (testcontainers-keycloak + realm-export); member2 GET member1's case → 404.
- `WebSocketAuthIT` — CONNECT without token rejected; member cannot SUBSCRIBE to another
  member's `/topic/cases/{id}`.
- Use Awaitility for async assertions (no `Thread.sleep`).

### Security
- JWT validation incl. wrong `iss`/`aud`/expired → 401; `@PreAuthorize` enforcement;
  resource-level ownership checks; `/internal/**` rejects user tokens (needs client-credentials scope).

---

## 12. BUILD SEQUENCE (hand to Claude Code in this order)

1. Parent POM + `common` (events, @Audited, role constants, error model).
2. `case-service`: entity + **state machine** (TDD first) + service + controller + security.
3. docker-compose: MySQL + Kafka + Redis + Keycloak + DynamoDB-local; `keycloak/realm-export.json`.
4. Keycloak wiring: services as resource servers; JWT→roles converter; `@PreAuthorize`. **(closes Keycloak gap)**
5. `AuditAspect` in common + `audit-service` consuming → DynamoDB. **(closes AOP gap)**
6. `responder-service` + assignment; case→responder call with Resilience4j.
7. `notification-service`: WebSocket + email.
8. `reporting-service`: SLA + S3 export.
9. `api-gateway`: routing + auth.
10. `portal`: React/TS — Keycloak login, case list/detail/timeline, triage/assign/resolve actions, live updates.
11. SAML federation in Keycloak (bonus). CloudFormation. CI/CD (GitHub Actions). K8s (bonus).

**Minimum viable for interview (if short on time): steps 1-5** → closes BOTH the
Keycloak and Spring AOP gaps and demonstrates Spring Boot microservices, JPA/Hibernate,
state-machine design, DynamoDB, and TDD. Highest gap-closing value per hour.

In the minimum slice, `assign` takes an explicit `responderId` validated against a
seeded table inside case-service (stub); swap for responder-service in step 6. Keep the
outbox from step 2 — it's small and a strong architecture talking point.

### Definition of done (per step)
- `mvn verify` green including that step's §11 tests; Flyway migration added.
- `docker compose up` brings the step up with healthchecks passing.
- README updated: what was built, how to demo it (curl/HTTPie script with tokens from
  `scripts/get-token.sh <user>`).

---

## 13. README / DESIGN-DOC CHECKLIST
- Architecture diagram; design decisions (state machine, event-driven choreography,
  why AOP for audit, why DynamoDB for immutable log, Keycloak role/realm model, SAML).
- Keycloak realm-export.json in repo (shows real config).
- "What I personally built" + "built AI-first with Claude Code/Cursor".
- Run instructions (docker-compose up), seeded users table (marked demo-only).
- Known limitations & production path: async audit publish (→ outbox for audit too),
  DynamoDB-local ignores IAM, single-region, no PII encryption at field level (→ KMS),
  WS scope cache staleness window (60 s).
- Observability: Micrometer metrics, `traceId` propagated via Kafka headers and stored
  on AuditRecord, structured JSON logs.

---

## TWO-APP STRATEGY RECAP
- **App 1** closes IoT/MQTT/AWS IoT (+ real-time, Spring microservices).
- **App 2** closes Keycloak/OIDC/SAML + Spring AOP (+ DynamoDB, state machines, enterprise arch).
- Shared across both: Spring Boot, JPA/Hibernate, MySQL, microservices, Kafka, Redis,
  WebSockets, Docker/K8s, AWS, JUnit/TDD, Angular/React-TS, AI-first build.
- Together = every named Global Rescue requirement, with your 3 gaps turned into
  "here's an app where I built exactly that."
- If time before Oct 12 is tight: App 1 steps 1-7+11 AND App 2 steps 1-5 are the
  highest-value slices — but even ONE app's core meaningfully strengthens you.
- **Recommended order with ~5 days:** App 2 steps 1–5 fully done and green first
  (≈3 days), then App 1's "even tighter slice" (≈1–2 days). A finished, tested slice
  beats two half-built systems in an interview.
- Share `common` patterns between the apps (JWT converter, problem+json, `@Audited`,
  Keycloak issuer setup) — build once in App 2, copy into App 1.
