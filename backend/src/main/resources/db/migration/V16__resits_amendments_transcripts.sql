-- =====================================================================
-- CIMS V16: supplementary and special examinations (FR-EXM-08, BP-10),
-- amendments to confirmed results (BP-08 exception), statements of
-- results / provisional transcripts (FR-EXM-13) and NACTVET results
-- submissions (FR-EXM-11).
-- =====================================================================

-- A result sheet is either the main sheet of a class or its sheet after supplementary and special examinations
ALTER TABLE result_sheets ADD COLUMN round VARCHAR(6) NOT NULL DEFAULT 'MAIN' CHECK (round IN ('MAIN', 'RESIT'));
ALTER TABLE result_sheets DROP CONSTRAINT uq_result_sheets;
ALTER TABLE result_sheets ADD CONSTRAINT uq_result_sheets UNIQUE (tenant_id, semester_id, programme_id, year_of_study, round);

-- After resits a failed module is carried over to be repeated
ALTER TABLE semester_results DROP CONSTRAINT semester_results_decision_check;
ALTER TABLE semester_results ADD CONSTRAINT semester_results_decision_check
    CHECK (decision IN ('PASS', 'SUPPLEMENTARY', 'SPECIAL_EXAM', 'INCOMPLETE', 'REPEAT', 'DISCONTINUE', 'CARRY_OVER'));

-- Resit sessions: one per semester, opened by the Examinations Officer
CREATE TABLE resit_sessions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id),
    semester_id          BIGINT        NOT NULL REFERENCES semesters (id),
    supplementary_fee    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (supplementary_fee >= 0),   -- per module
    special_fee          NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (special_fee >= 0),         -- per module
    registration_closes  DATE          NOT NULL,
    exam_period          VARCHAR(120),
    created_by_name      VARCHAR(150),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_resit_sessions UNIQUE (tenant_id, semester_id)
);

-- A student's supplementary or special examination in one module, and its outcome
CREATE TABLE resits (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    session_id         BIGINT        NOT NULL REFERENCES resit_sessions (id),
    module_result_id   BIGINT        NOT NULL REFERENCES module_results (id),
    offering_id        BIGINT        NOT NULL REFERENCES module_offerings (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    kind               VARCHAR(14)   NOT NULL CHECK (kind IN ('SUPPLEMENTARY', 'SPECIAL')),
    status             VARCHAR(10)   NOT NULL DEFAULT 'REGISTERED' CHECK (status IN ('REGISTERED', 'CANCELLED')),
    fee                NUMERIC(12,2) NOT NULL DEFAULT 0,
    invoice_id         BIGINT        REFERENCES invoices (id),
    registered_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    score              NUMERIC(6,2)  CHECK (score BETWEEN 0 AND 100),          -- the resit examination, out of 100
    absent             BOOLEAN       NOT NULL DEFAULT FALSE,
    ca_total           NUMERIC(5,2),                                           -- carried from the first attempt
    se_total           NUMERIC(5,2),
    total              NUMERIC(5,2),
    grade              VARCHAR(3),
    grade_points       NUMERIC(3,1),
    remark             VARCHAR(20)   CHECK (remark IN ('PASS', 'FAIL', 'INCOMPLETE', 'ABSENT')),
    fail_reason        VARCHAR(80),
    capped             BOOLEAN       NOT NULL DEFAULT FALSE,
    entered_by_name    VARCHAR(150),
    entered_at         TIMESTAMPTZ,
    locked             BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_resits UNIQUE (module_result_id),
    CONSTRAINT ck_resit_score_or_absent CHECK (NOT (absent AND score IS NOT NULL))
);
CREATE INDEX ix_resits_offering ON resits (tenant_id, offering_id);
CREATE INDEX ix_resits_student ON resits (tenant_id, student_id);
CREATE TRIGGER tr_resits_locked BEFORE UPDATE OR DELETE ON resits FOR EACH ROW EXECUTE FUNCTION cims_protect_locked_results();

-- Resit marks of a module go lecturer → Head of Department
ALTER TABLE module_offerings ADD COLUMN resit_status VARCHAR(10) CHECK (resit_status IN ('DRAFT', 'SUBMITTED', 'APPROVED'));
ALTER TABLE module_offerings ADD COLUMN resit_submitted_by_name VARCHAR(150);
ALTER TABLE module_offerings ADD COLUMN resit_submitted_at TIMESTAMPTZ;
ALTER TABLE module_offerings ADD COLUMN resit_approved_by_name VARCHAR(150);
ALTER TABLE module_offerings ADD COLUMN resit_approved_at TIMESTAMPTZ;
ALTER TABLE module_offerings ADD COLUMN resit_returned_note VARCHAR(1000);

-- Changes to confirmed results: requested with a reason, approved by someone else, then applied
CREATE TABLE result_amendments (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    module_result_id   BIGINT        NOT NULL REFERENCES module_results (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    old_ca_total       NUMERIC(5,2),
    old_se_total       NUMERIC(5,2),
    old_total          NUMERIC(5,2),
    old_grade          VARCHAR(3),
    old_remark         VARCHAR(20),
    new_ca_total       NUMERIC(5,2)  NOT NULL CHECK (new_ca_total >= 0),
    new_se_total       NUMERIC(5,2)  NOT NULL CHECK (new_se_total >= 0),
    new_total          NUMERIC(5,2),
    new_grade          VARCHAR(3),
    new_remark         VARCHAR(20),
    reason             VARCHAR(1000) NOT NULL,
    evidence_ref       VARCHAR(200),
    status             VARCHAR(10)   NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    requested_by       BIGINT,
    requested_by_name  VARCHAR(150)  NOT NULL,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by_name    VARCHAR(150),
    decided_at         TIMESTAMPTZ,
    decision_note      VARCHAR(1000)
);
CREATE INDEX ix_result_amendments_status ON result_amendments (tenant_id, status);
CREATE UNIQUE INDEX uq_result_amendments_pending ON result_amendments (module_result_id) WHERE status = 'PENDING';

-- Issued statements of results / provisional transcripts, verifiable by code (BP-16)
CREATE TABLE academic_documents (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    kind               VARCHAR(20)   NOT NULL CHECK (kind IN ('TRANSCRIPT')),
    document_no        VARCHAR(40)   NOT NULL,
    verification_code  VARCHAR(19)   NOT NULL,
    status             VARCHAR(12)   NOT NULL CHECK (status IN ('VALID', 'SUPERSEDED', 'REVOKED')),
    content            JSONB         NOT NULL,
    content_sha256     CHAR(64)      NOT NULL,
    issued_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    issued_by_name     VARCHAR(150)  NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_academic_documents_no UNIQUE (tenant_id, document_no)
);
CREATE UNIQUE INDEX uq_academic_documents_code ON academic_documents (verification_code);
CREATE INDEX ix_academic_documents_student ON academic_documents (tenant_id, student_id, kind, status);

-- Results files sent to NACTVET and what NACTVET said
CREATE TABLE nactvet_result_submissions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    result_sheet_id    BIGINT        NOT NULL REFERENCES result_sheets (id),
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'QUERIED')),
    reference          VARCHAR(100),
    note               VARCHAR(1000),
    students           INTEGER       NOT NULL,
    submitted_by_name  VARCHAR(150)  NOT NULL,
    submitted_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    response_by_name   VARCHAR(150),
    response_at        TIMESTAMPTZ
);
CREATE INDEX ix_nactvet_result_submissions_sheet ON nactvet_result_submissions (tenant_id, result_sheet_id);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['resit_sessions', 'resits', 'result_amendments', 'academic_documents', 'nactvet_result_submissions']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
