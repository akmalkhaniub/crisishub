# CrisisHub — Incident Response & Case Management System

Architecture Spec: [`SPEC.md`](SPEC.md).
This repo implements the core distributed architecture for crisis and incident response: the parent POM and `common` module, case-service with its state machine, docker-compose, Keycloak wiring, and the audit aspect with audit-service writing to DynamoDB.

```
member/operator/responder ──JWT──▶ case-service ──@Audited──▶ audit.events ──▶ audit-service ──▶ DynamoDB audit_log
   (Keycloak, realm               │  FSM + ownership checks                       (conditional put,      (immutable,
    incident-response)            │  MySQL: case_record, case_event,               idempotent)            GSI actor-index)
                                  │         outbox_event
                                  └─ outbox relay ──▶ case.events / assignment.events (consumed from step 6)
```

| Module | Port | What it does |
|---|---|---|
| `common` | – | event records, `@Audited` + `AuditAspect`, Keycloak JWT converter and issuer/audience-validating `JwtDecoder`, problem+json errors, Kafka DLT error handler |
| `case-service` | 8081 | case lifecycle: enum state machine, role and ownership checks, timeline, transactional outbox, SLA breach scanner |
| `audit-service` | 8084 | consumes `audit.events` and appends to DynamoDB; query API for auditors and admins |

## Run it

Prerequisites: JDK 21, Maven 3.9, and Docker (for compose and the integration tests).

```bash
docker compose up -d --build
```

```bash
bash scripts/demo.sh
```

`demo.sh` runs a full lifecycle (report → triage → assign → resolve → close). Along the way it shows a 403 for a role violation, a 404 for another member's case, a 409 for an illegal transition, then prints the timeline and the DynamoDB audit trail.

To call the API yourself:

```bash
TOKEN=$(scripts/get-token.sh op1); curl -H "Authorization: Bearer $TOKEN" localhost:8081/api/cases
```

### Seeded users (DEMO ONLY: password `demo1234` for all)

| User | Group → role | Keycloak id (`sub`) |
|---|---|---|
| member1, member2 | Members → member | …0001, …0007 |
| op1 | Operations → operator | …0002 |
| resp1, resp2 | Field → responder | …0004, …0005 (linked to `responder_stub` rows) |
| aud1 | Compliance → auditor | …0003 |
| admin | Administrators → admin (composite: operator + auditor) | …0006 |

Keycloak admin console: http://localhost:8180 (`kcadmin` / `kcadmin-dev-only`).

## Tests

```bash
mvn verify
```

- **Unit and slice tests (121, no Docker needed)**
  - Every one of the 36 state-machine pairs.
  - Service guards: assigned responder, reopen keeps the SLA, pending assignment, member cancel rules, 404 on another member's case.
  - SLA table.
  - `AuditAspect`: actor, entity id, FAILED events, a publisher failure doesn't break the call.
  - ArchUnit: `@Audited` only on public `@Service` methods.
  - Role matrix for every endpoint, including the audit API.
  - Sort-key ordering and page tokens.
- **Integration tests (Testcontainers, skipped automatically without Docker)**
  - `CaseLifecycleIT`: MySQL + Kafka, full lifecycle, outbox drains, exactly one audit event per action.
  - `AuditFlowIT`: Kafka + DynamoDB-local, a duplicate delivery is stored once, query by entity and actor, pagination.

## Design decisions (interview talking points)

- **AOP for audit.** Audit is a cross-cutting concern, so it's handled with AOP: one annotation and zero audit code in the business logic.
  - The pointcut binds the annotation as `@annotation(audited)`, lowercase.
  - The actor is the JWT `sub`, or `system` on Kafka and scheduler threads.
  - The aspect runs *outside* `@Transactional`, so OK means committed and a rollback produces FAILED.
  - Raw method arguments are never logged.
  - Spring proxies don't intercept calls from inside the same bean. That's why `@Audited` sits on the public `CaseService` methods and never on `applyTransition`, and an ArchUnit test enforces it.
- **State machine.** It's a pure enum map rather than Spring StateMachine.
  - Several actions share the target IN_PROGRESS (assign, reassign, reopen), so the service also checks the source state.
  - No path skips TRIAGED, and illegal transitions return a 409 problem+json response.
- **Transactional outbox.** Domain and assignment events are written in the same transaction as the state change. A ShedLock-guarded relay publishes them, so nothing is published if the transaction rolls back.
- **Immutable audit.**
  - The DynamoDB key `entityId` + `<fixed-width timestamp>#<eventId>` is built from producer-side fields. Combined with `attribute_not_exists`, a redelivered event is a no-op.
  - The timestamp is fixed-width because `Instant.toString()` doesn't sort lexically.
  - The service has no update or delete code path. In AWS the IAM policy would also deny `UpdateItem`/`DeleteItem`.
  - Reading the audit trail is itself audited (`AUDIT_QUERIED`).
- **Keycloak.**
  - Realm roles are assigned via groups; `admin` is a composite role.
  - Tokens must carry `aud=ir-api` (added by an audience mapper).
  - `KC_HOSTNAME` pins `iss` to the URL the browser uses. Services validate that issuer but fetch keys from the internal `keycloak:8080` URL, which avoids the classic "works in the browser, 401 in Docker" problem.
  - The principal is `sub`, never the username.
- **Authorization in two layers.**
  - Role gates use `@PreAuthorize` on the controller.
  - Resource checks happen in the service: members see their own cases, responders see cases assigned to them.
  - A case outside the caller's scope returns 404 rather than 403, so case ids can't be probed.
- **Concurrency.** `@Version` on cases and responders means a lost update or double-booking becomes a 409.

## Known gaps / next steps

- **Step 6:** responder-service replaces `StubResponderClient`. It will use a client-credentials token (`ir-case-service`) and a Resilience4j breaker, and the pending-assignment retry job comes with it.
- **Steps 7–11:** notification-service, reporting-service, api-gateway, the portal, SAML federation, CloudFormation and CI.
- **Audit publishing is fire-and-forget.** It runs after commit but isn't transactional with it; the production upgrade is to route audit events through the outbox too.
- **No `If-Match`/ETag yet.** Concurrent edits rely on `@Version` alone, which gives a 409.
- **Field added beyond the spec:** `CaseRecord.assignedResponderUserId`, which stores the responder's Keycloak `sub` for the "assigned responder only" check.
- **DynamoDB-local ignores IAM**, so the deny-update/delete policy only applies in real AWS.
- **Demo credentials:** the `ir-cli` password-grant client and the seeded passwords exist for local demos only.

## What I built

Built AI-first with Claude Code, against the written spec as the source of truth. Each step was test-first: the state machine, the service guards and the aspect tests were written before wiring up the infrastructure.
