-- =====================================================================
-- CIMS V20: industrial practical training (FR-IPT-01..03, BR-21,
-- BP-13): placement organisations and contacts, IPT periods tied to
-- the IPT module of a semester, student placements with workplace and
-- institution supervisors and placement letters, weekly electronic
-- logbooks reviewed by the supervisor, supervision visits, supervisor
-- assessments, and transfer of IPT marks to the examination record.
-- =====================================================================

CREATE TABLE placement_organisations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    name             VARCHAR(200)  NOT NULL,
    sector           VARCHAR(100),
    region           VARCHAR(80),
    district         VARCHAR(80),
    address          VARCHAR(300),
    contact_name     VARCHAR(150),
    contact_title    VARCHAR(120),
    contact_phone    VARCHAR(20),
    contact_email    VARCHAR(150),
    capacity         INTEGER       CHECK (capacity IS NULL OR capacity >= 0),   -- students it can take per period
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    proposed_by_student BOOLEAN    NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_placement_organisations_name ON placement_organisations (tenant_id, lower(name));

-- An IPT period: when field attachment happens, for which IPT module of which semester, and how it is assessed
CREATE TABLE ipt_periods (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES tenants (id),
    name                  VARCHAR(120)  NOT NULL,
    semester_id           BIGINT        NOT NULL REFERENCES semesters (id),
    module_id             BIGINT        REFERENCES modules (id),       -- the IPT module whose marks the results feed
    starts_on             DATE          NOT NULL,
    ends_on               DATE          NOT NULL,
    requests_close        DATE,                                         -- students may propose a placement until then
    workplace_weight      NUMERIC(5,2)  NOT NULL DEFAULT 40,
    institution_weight    NUMERIC(5,2)  NOT NULL DEFAULT 30,
    logbook_weight        NUMERIC(5,2)  NOT NULL DEFAULT 30,
    min_logbook_weeks     SMALLINT      NOT NULL DEFAULT 1 CHECK (min_logbook_weeks >= 0),
    results_transferred_at TIMESTAMPTZ,
    results_transferred_by VARCHAR(150),
    created_by_name       VARCHAR(150)  NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_ipt_periods_dates CHECK (ends_on >= starts_on),
    CONSTRAINT ck_ipt_periods_weights CHECK (workplace_weight + institution_weight + logbook_weight = 100)
);

CREATE TABLE ipt_placements (
    id                         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                  BIGINT        NOT NULL REFERENCES tenants (id),
    period_id                  BIGINT        NOT NULL REFERENCES ipt_periods (id),
    student_id                 BIGINT        NOT NULL REFERENCES students (id),
    organisation_id            BIGINT        NOT NULL REFERENCES placement_organisations (id),
    status                     VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED'
                               CHECK (status IN ('REQUESTED', 'CONFIRMED', 'COMPLETED', 'CANCELLED', 'DECLINED')),
    starts_on                  DATE          NOT NULL,
    ends_on                    DATE          NOT NULL,
    unit                       VARCHAR(150),                  -- department or section at the workplace
    workplace_supervisor_name  VARCHAR(150),
    workplace_supervisor_title VARCHAR(120),
    workplace_supervisor_phone VARCHAR(20),
    workplace_supervisor_email VARCHAR(150),
    institution_supervisor_id  BIGINT        REFERENCES user_accounts (id),
    student_note               VARCHAR(500),
    decision_note              VARCHAR(500),
    letter_no                  VARCHAR(40),
    letter_issued_at           TIMESTAMPTZ,
    requested_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    confirmed_by_name          VARCHAR(150),
    confirmed_at               TIMESTAMPTZ,
    total_mark                 NUMERIC(5,2),
    transferred_at             TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                    BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_ipt_placements_dates CHECK (ends_on >= starts_on)
);
-- one live placement per student per period
CREATE UNIQUE INDEX uq_ipt_placements_student ON ipt_placements (period_id, student_id) WHERE status IN ('REQUESTED', 'CONFIRMED', 'COMPLETED');
CREATE INDEX ix_ipt_placements_period ON ipt_placements (tenant_id, period_id);
CREATE INDEX ix_ipt_placements_supervisor ON ipt_placements (tenant_id, institution_supervisor_id);

-- A weekly logbook entry
CREATE TABLE ipt_logbook_entries (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    placement_id      BIGINT         NOT NULL REFERENCES ipt_placements (id) ON DELETE CASCADE,
    week_no           SMALLINT       NOT NULL CHECK (week_no BETWEEN 1 AND 60),
    week_starting     DATE           NOT NULL,
    activities        VARCHAR(4000)  NOT NULL,
    skills            VARCHAR(2000),
    challenges        VARCHAR(2000),
    hours             NUMERIC(5,1)   CHECK (hours IS NULL OR hours BETWEEN 0 AND 100),
    status            VARCHAR(10)    NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'RETURNED')),
    submitted_at      TIMESTAMPTZ,
    reviewer_comment  VARCHAR(1000),
    reviewed_by_name  VARCHAR(150),
    reviewed_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_ipt_logbook_entries UNIQUE (placement_id, week_no)
);

-- A supervision visit by the institution supervisor
CREATE TABLE ipt_visits (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    placement_id     BIGINT         NOT NULL REFERENCES ipt_placements (id) ON DELETE CASCADE,
    visited_on       DATE           NOT NULL,
    mode             VARCHAR(10)    NOT NULL CHECK (mode IN ('VISIT', 'PHONE', 'ONLINE')),
    notes            VARCHAR(2000)  NOT NULL,
    recorded_by_name VARCHAR(150)   NOT NULL,
    recorded_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- The three marks that make up the IPT result, each out of 100
CREATE TABLE ipt_assessments (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    placement_id      BIGINT         NOT NULL REFERENCES ipt_placements (id) ON DELETE CASCADE,
    kind              VARCHAR(12)    NOT NULL CHECK (kind IN ('WORKPLACE', 'INSTITUTION', 'LOGBOOK')),
    score             NUMERIC(5,2)   NOT NULL CHECK (score BETWEEN 0 AND 100),
    comments          VARCHAR(2000),
    assessed_by_name  VARCHAR(150)   NOT NULL,
    assessed_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version           BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_ipt_assessments UNIQUE (placement_id, kind)
);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['placement_organisations', 'ipt_periods', 'ipt_placements', 'ipt_logbook_entries', 'ipt_visits', 'ipt_assessments']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
