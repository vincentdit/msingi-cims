-- =====================================================================
-- CIMS V18: clearance and graduation (FR-GRD-01..04, BR-29/30, BP-15,
-- UC-18): clearance offices and online clearance, graduations with
-- graduand lists for Governing Board approval and NACTVET submission,
-- award certificates with verification codes and a print log.
-- =====================================================================

-- Offices a finalist must be cleared by. DEPARTMENT: the Head of the student's department; FINANCE: automatic from the
-- student's balance; STAFF: the officers assigned to the office (e.g. library, hostel, sports)
CREATE TABLE clearance_offices (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    code          VARCHAR(20)   NOT NULL,
    name          VARCHAR(120)  NOT NULL,
    kind          VARCHAR(10)   NOT NULL CHECK (kind IN ('DEPARTMENT', 'FINANCE', 'STAFF')),
    instructions  VARCHAR(500),                 -- what the student must do, shown on the portal
    sort_order    SMALLINT      NOT NULL DEFAULT 0,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_clearance_offices UNIQUE (tenant_id, code)
);

CREATE TABLE clearance_office_staff (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT  NOT NULL REFERENCES tenants (id),
    office_id  BIGINT  NOT NULL REFERENCES clearance_offices (id) ON DELETE CASCADE,
    user_id    BIGINT  NOT NULL REFERENCES user_accounts (id),
    CONSTRAINT uq_clearance_office_staff UNIQUE (office_id, user_id)
);

-- A finalist's clearance, one per student
CREATE TABLE clearances (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    student_id       BIGINT        NOT NULL REFERENCES students (id),
    status           VARCHAR(12)   NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'CLEARED')),
    started_by_name  VARCHAR(150)  NOT NULL,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    cleared_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_clearances UNIQUE (tenant_id, student_id)
);

-- One office's decision on a clearance
CREATE TABLE clearance_items (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    clearance_id     BIGINT        NOT NULL REFERENCES clearances (id) ON DELETE CASCADE,
    office_id        BIGINT        NOT NULL REFERENCES clearance_offices (id),
    status           VARCHAR(8)    NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CLEARED', 'HELD')),
    note             VARCHAR(500),               -- the reason for a hold, or a remark on clearing
    decided_by_name  VARCHAR(150),
    decided_at       TIMESTAMPTZ,
    CONSTRAINT uq_clearance_items UNIQUE (clearance_id, office_id),
    CONSTRAINT ck_clearance_items_hold CHECK (status <> 'HELD' OR note IS NOT NULL)
);
CREATE INDEX ix_clearance_items_office ON clearance_items (tenant_id, office_id, status);

-- A graduation: its graduand list goes to the Governing Board, then to NACTVET
CREATE TABLE graduations (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT        NOT NULL REFERENCES tenants (id),
    name                   VARCHAR(120)  NOT NULL,
    ceremony_date          DATE,
    status                 VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED')),
    generated_by_name      VARCHAR(150),
    generated_at           TIMESTAMPTZ,
    submitted_by_id        BIGINT,
    submitted_by_name      VARCHAR(150),
    submitted_at           TIMESTAMPTZ,
    approved_by_name       VARCHAR(150),
    approved_at            TIMESTAMPTZ,
    board_minute_ref       VARCHAR(100),
    returned_note          VARCHAR(1000),
    returned_by_name       VARCHAR(150),
    nactvet_status         VARCHAR(10)   CHECK (nactvet_status IN ('SUBMITTED', 'ACCEPTED', 'QUERIED')),
    nactvet_reference      VARCHAR(100),
    nactvet_note           VARCHAR(1000),
    nactvet_submitted_at   TIMESTAMPTZ,
    created_by_name        VARCHAR(150)  NOT NULL,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0
);

-- A student on a graduation's list, with the award as it stood when listed, and their certificate
CREATE TABLE graduands (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT        NOT NULL REFERENCES tenants (id),
    graduation_id            BIGINT        NOT NULL REFERENCES graduations (id) ON DELETE CASCADE,
    student_id               BIGINT        NOT NULL REFERENCES students (id),
    programme_id             BIGINT        NOT NULL REFERENCES programmes (id),
    award_name               VARCHAR(200)  NOT NULL,
    classification           VARCHAR(60),
    cgpa                     NUMERIC(4,2),
    credits_earned           NUMERIC(6,1),
    completed_semester       VARCHAR(80)   NOT NULL,      -- e.g. "Semester 2 2028/2029"
    certificate_no           VARCHAR(40),
    verification_code        VARCHAR(30),
    certificate_issued_at    TIMESTAMPTZ,
    certificate_issued_by    VARCHAR(150),
    print_count              INTEGER       NOT NULL DEFAULT 0,
    last_printed_at          TIMESTAMPTZ,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_graduands_student UNIQUE (tenant_id, student_id)
);
CREATE INDEX ix_graduands_graduation ON graduands (tenant_id, graduation_id);
CREATE UNIQUE INDEX uq_graduands_certificate ON graduands (tenant_id, certificate_no) WHERE certificate_no IS NOT NULL;
-- Verification codes are looked up across institutions by the public verification page
CREATE UNIQUE INDEX uq_graduands_verification ON graduands (verification_code) WHERE verification_code IS NOT NULL;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['clearance_offices', 'clearance_office_staff', 'clearances', 'clearance_items', 'graduations', 'graduands']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
