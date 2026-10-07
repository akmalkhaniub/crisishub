CREATE TABLE case_record (
    id                         CHAR(36)     NOT NULL PRIMARY KEY,
    case_number                VARCHAR(32)  NOT NULL,
    type                       VARCHAR(32)  NOT NULL,
    reported_severity          VARCHAR(16),
    severity                   VARCHAR(16),
    status                     VARCHAR(16)  NOT NULL,
    assignment_pending         BOOLEAN      NOT NULL DEFAULT FALSE,
    member_id                  VARCHAR(64)  NOT NULL,
    title                      VARCHAR(200) NOT NULL,
    description                VARCHAR(4000),
    lat                        DOUBLE,
    lng                        DOUBLE,
    assigned_responder_id      CHAR(36),
    assigned_responder_user_id VARCHAR(64),
    reported_at                DATETIME(6),
    triaged_at                 DATETIME(6),
    assigned_at                DATETIME(6),
    resolved_at                DATETIME(6),
    closed_at                  DATETIME(6),
    cancelled_at               DATETIME(6),
    sla_due_at                 DATETIME(6),
    sla_breached_at            DATETIME(6),
    reopen_count               INT          NOT NULL DEFAULT 0,
    cancel_reason              VARCHAR(500),
    resolution_note            VARCHAR(4000),
    version                    BIGINT       NOT NULL DEFAULT 0,
    created_at                 DATETIME(6),
    updated_at                 DATETIME(6),
    CONSTRAINT uk_case_number UNIQUE (case_number)
);
CREATE INDEX ix_case_status ON case_record (status);
CREATE INDEX ix_case_member ON case_record (member_id);
CREATE INDEX ix_case_responder_user ON case_record (assigned_responder_user_id);
CREATE INDEX ix_case_sla ON case_record (sla_due_at);

CREATE TABLE case_event (
    id          CHAR(36)    NOT NULL PRIMARY KEY,
    case_id     CHAR(36)    NOT NULL,
    event_type  VARCHAR(32) NOT NULL,
    from_status VARCHAR(16),
    to_status   VARCHAR(16),
    detail      VARCHAR(500),
    actor       VARCHAR(64) NOT NULL,
    at          DATETIME(6) NOT NULL
);
CREATE INDEX ix_case_event_case ON case_event (case_id, at);

CREATE TABLE outbox_event (
    id           CHAR(36)     NOT NULL PRIMARY KEY,
    topic        VARCHAR(64)  NOT NULL,
    msg_key      VARCHAR(64)  NOT NULL,
    payload_json TEXT         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)
);
CREATE INDEX ix_outbox_unpublished ON outbox_event (published_at, created_at);

CREATE TABLE case_number_seq (
    seq_year INT    NOT NULL PRIMARY KEY,
    next_val BIGINT NOT NULL
);

-- Minimum-slice responder table (replaced by responder-service in build step 6).
CREATE TABLE responder_stub (
    id                   CHAR(36)     NOT NULL PRIMARY KEY,
    user_id              VARCHAR(64)  NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    skills               VARCHAR(200) NOT NULL,
    available            BOOLEAN      NOT NULL,
    active_case_count    INT          NOT NULL DEFAULT 0,
    max_concurrent_cases INT          NOT NULL DEFAULT 2,
    version              BIGINT       NOT NULL DEFAULT 0
);

-- user_id values are the fixed Keycloak user ids from keycloak/realm-export.json
INSERT INTO responder_stub (id, user_id, name, skills, available) VALUES
    ('a0000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000004', 'Responder One', 'MEDICAL,EVACUATION', TRUE),
    ('a0000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000005', 'Responder Two', 'SECURITY,LOGISTICS,MEDICAL', TRUE);

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL
);
