-- Helpdesk (FR-HLP). Applicants, students, staff and people without an account ask the institution for help; each request
-- (ticket) is routed by its category to the office that answers it, with response and resolution targets, escalation when
-- they are missed, SMS/email updates and attachments. A knowledge base answers common questions before a ticket is raised.
-- Institution administrators also raise support tickets to Msingi Technologies, answered in the platform console.

-- What people can ask about, the office that answers, who may choose it and how fast it must be answered
CREATE TABLE helpdesk_categories (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    name              VARCHAR(100)  NOT NULL,
    description       VARCHAR(300),
    office_role       VARCHAR(40)   NOT NULL,                 -- staff with this role answer (INSTITUTION_ADMIN for ICT, BURSAR for fees …)
    for_applicants    BOOLEAN       NOT NULL DEFAULT FALSE,
    for_students      BOOLEAN       NOT NULL DEFAULT FALSE,
    for_staff         BOOLEAN       NOT NULL DEFAULT FALSE,
    for_guests        BOOLEAN       NOT NULL DEFAULT FALSE,   -- people without an account (the public help page)
    response_hours    INTEGER       NOT NULL DEFAULT 8  CHECK (response_hours BETWEEN 1 AND 720),
    resolution_hours  INTEGER       NOT NULL DEFAULT 72 CHECK (resolution_hours BETWEEN 1 AND 2160),
    active            BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order        INTEGER       NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (resolution_hours >= response_hours)
);
CREATE UNIQUE INDEX uq_helpdesk_categories_name ON helpdesk_categories (tenant_id, lower(name));

CREATE TABLE helpdesk_tickets (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id),
    ticket_no            VARCHAR(30)   NOT NULL,
    category_id          BIGINT        NOT NULL REFERENCES helpdesk_categories (id),
    office_role          VARCHAR(40)   NOT NULL,              -- the office answering now (from the category when routed)
    subject              VARCHAR(200)  NOT NULL,
    -- OPEN (new) → IN_PROGRESS → WAITING (for the requester; the resolution clock stops) → RESOLVED → CLOSED (after 7 days or by the requester)
    status               VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    priority             VARCHAR(8)    NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    requester_type       VARCHAR(10)   NOT NULL CHECK (requester_type IN ('APPLICANT', 'STUDENT', 'STAFF', 'GUEST')),
    requester_user_id    BIGINT        REFERENCES user_accounts (id),
    requester_name       VARCHAR(200)  NOT NULL,
    requester_phone      VARCHAR(20),
    requester_email      VARCHAR(120),
    access_code_hash     VARCHAR(64),                         -- guests follow their ticket with its number and this code
    assignee_user_id     BIGINT        REFERENCES user_accounts (id),
    assignee_name        VARCHAR(200),
    response_due_at      TIMESTAMPTZ   NOT NULL,
    resolution_due_at    TIMESTAMPTZ   NOT NULL,
    first_response_at    TIMESTAMPTZ,
    paused_at            TIMESTAMPTZ,                         -- while WAITING
    resolved_at          TIMESTAMPTZ,
    closed_at            TIMESTAMPTZ,
    escalation_level     SMALLINT      NOT NULL DEFAULT 0,    -- 1: response late (office told); 2: resolution late (administrators told)
    satisfied            BOOLEAN,                             -- the requester's answer when closing
    msingi_ticket_id     BIGINT,                              -- escalated to Msingi (support_tickets)
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_helpdesk_ticket_no UNIQUE (tenant_id, ticket_no),
    CHECK (requester_type = 'GUEST' OR requester_user_id IS NOT NULL),
    CHECK (requester_type <> 'GUEST' OR access_code_hash IS NOT NULL)
);
CREATE INDEX ix_helpdesk_tickets_queue ON helpdesk_tickets (tenant_id, office_role, status);
CREATE INDEX ix_helpdesk_tickets_requester ON helpdesk_tickets (requester_user_id);
CREATE INDEX ix_helpdesk_tickets_assignee ON helpdesk_tickets (assignee_user_id) WHERE assignee_user_id IS NOT NULL;
CREATE INDEX ix_helpdesk_tickets_open_due ON helpdesk_tickets (tenant_id, resolution_due_at) WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING');

-- The conversation: the requester's and staff's messages, staff-only notes, and what happened (routing, assignment, escalation)
CREATE TABLE helpdesk_messages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    ticket_id       BIGINT        NOT NULL REFERENCES helpdesk_tickets (id) ON DELETE CASCADE,
    author_type     VARCHAR(10)   NOT NULL CHECK (author_type IN ('REQUESTER', 'STAFF', 'SYSTEM')),
    author_user_id  BIGINT        REFERENCES user_accounts (id),
    author_name     VARCHAR(200)  NOT NULL,
    body            TEXT          NOT NULL CHECK (length(body) <= 5000),
    internal        BOOLEAN       NOT NULL DEFAULT FALSE,     -- staff only, never shown to the requester
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_helpdesk_messages_ticket ON helpdesk_messages (ticket_id, created_at);

CREATE TABLE helpdesk_attachments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    message_id    BIGINT        NOT NULL REFERENCES helpdesk_messages (id) ON DELETE CASCADE,
    file_name     VARCHAR(160)  NOT NULL,
    content_type  VARCHAR(40)   NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes    INTEGER       NOT NULL,
    sha256        VARCHAR(64)   NOT NULL,
    data          BYTEA         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_helpdesk_attachments_message ON helpdesk_attachments (message_id);

-- Knowledge base: answers to common questions, shown on the help pages before a ticket is raised
CREATE TABLE helpdesk_articles (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    category_id      BIGINT        REFERENCES helpdesk_categories (id),
    title            VARCHAR(200)  NOT NULL,
    body             TEXT          NOT NULL CHECK (length(body) <= 10000),
    for_applicants   BOOLEAN       NOT NULL DEFAULT FALSE,
    for_students     BOOLEAN       NOT NULL DEFAULT FALSE,
    for_staff        BOOLEAN       NOT NULL DEFAULT FALSE,
    for_public       BOOLEAN       NOT NULL DEFAULT FALSE,    -- on the public help page, without signing in
    published        BOOLEAN       NOT NULL DEFAULT FALSE,
    views            INTEGER       NOT NULL DEFAULT 0,
    helpful          INTEGER       NOT NULL DEFAULT 0,
    not_helpful      INTEGER       NOT NULL DEFAULT 0,
    updated_by_name  VARCHAR(200)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_helpdesk_articles_published ON helpdesk_articles (tenant_id, published);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['helpdesk_categories', 'helpdesk_tickets', 'helpdesk_messages', 'helpdesk_attachments', 'helpdesk_articles']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------------------------------------------------------
-- Support from Msingi Technologies: an institution's administrators raise tickets that Msingi staff answer in the platform
-- console. An institution sees only its own tickets (and never Msingi's internal notes); the platform sees them all.

CREATE SEQUENCE support_ticket_seq;

CREATE TABLE support_tickets (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT        NOT NULL REFERENCES tenants (id),
    ticket_no                VARCHAR(30)   NOT NULL UNIQUE,
    category                 VARCHAR(20)   NOT NULL CHECK (category IN ('ACCOUNT_ACCESS', 'PROBLEM', 'INTEGRATION', 'DATA_CHANGE',
                                                                         'TRAINING', 'NEW_FEATURE', 'BILLING', 'OTHER')),
    subject                  VARCHAR(200)  NOT NULL,
    status                   VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'WAITING', 'RESOLVED', 'CLOSED')),
    priority                 VARCHAR(8)    NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'HIGH', 'URGENT')),
    raised_by_user_id        BIGINT        NOT NULL REFERENCES user_accounts (id),
    raised_by_name           VARCHAR(200)  NOT NULL,
    raised_by_email          VARCHAR(120),
    raised_by_phone          VARCHAR(20),
    assignee_platform_user_id BIGINT       REFERENCES platform_users (id),
    assignee_name            VARCHAR(150),
    response_due_at          TIMESTAMPTZ   NOT NULL,
    resolution_due_at        TIMESTAMPTZ   NOT NULL,
    first_response_at        TIMESTAMPTZ,
    paused_at                TIMESTAMPTZ,
    resolved_at              TIMESTAMPTZ,
    closed_at                TIMESTAMPTZ,
    escalation_level         SMALLINT      NOT NULL DEFAULT 0,
    satisfied                BOOLEAN,
    helpdesk_ticket_id       BIGINT        REFERENCES helpdesk_tickets (id),   -- the institution's own ticket it came from
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_support_tickets_tenant ON support_tickets (tenant_id, status);
CREATE INDEX ix_support_tickets_open_due ON support_tickets (resolution_due_at) WHERE status IN ('OPEN', 'IN_PROGRESS', 'WAITING');

ALTER TABLE helpdesk_tickets ADD CONSTRAINT fk_helpdesk_msingi_ticket FOREIGN KEY (msingi_ticket_id) REFERENCES support_tickets (id);

CREATE TABLE support_messages (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    ticket_id         BIGINT        NOT NULL REFERENCES support_tickets (id) ON DELETE CASCADE,
    author_side       VARCHAR(12)   NOT NULL CHECK (author_side IN ('INSTITUTION', 'MSINGI', 'SYSTEM')),
    author_name       VARCHAR(200)  NOT NULL,
    body              TEXT          NOT NULL CHECK (length(body) <= 5000),
    internal          BOOLEAN       NOT NULL DEFAULT FALSE,   -- Msingi staff only
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_messages_ticket ON support_messages (ticket_id, created_at);

CREATE TABLE support_attachments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    message_id    BIGINT        NOT NULL REFERENCES support_messages (id) ON DELETE CASCADE,
    file_name     VARCHAR(160)  NOT NULL,
    content_type  VARCHAR(40)   NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes    INTEGER       NOT NULL,
    sha256        VARCHAR(64)   NOT NULL,
    data          BYTEA         NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_attachments_message ON support_attachments (message_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['support_tickets', 'support_messages', 'support_attachments']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY platform_or_tenant ON %I USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())'
                       ' WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
