-- NVA competence-based assessment (BP-09, FR-EXM-09, BR-16).
-- A competence unit is one module assessed by competence in one semester (modules marked COMPETENCE, and every module of an
-- NVA programme). The Head of Department sets its tasks and the evidence each needs; assessors allocated to the unit record
-- each trainee's outcome per task (competent / not yet competent, optional practical and theory scores, date, assessor and
-- evidence reference); not-yet-competent tasks are re-assessed up to a limit; the Head of Department verifies the unit; the
-- Examinations Officer confirms award eligibility per class and exports the list for NACTVET.

ALTER TABLE exam_settings ADD COLUMN nva_max_attempts SMALLINT NOT NULL DEFAULT 3 CHECK (nva_max_attempts BETWEEN 1 AND 6);

CREATE TABLE competence_units (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    semester_id        BIGINT        NOT NULL REFERENCES semesters (id),
    module_id          BIGINT        NOT NULL REFERENCES modules (id),
    status             VARCHAR(10)   NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'SUBMITTED', 'VERIFIED')),
    returned_note      VARCHAR(1000),
    returned_by_name   VARCHAR(150),
    returned_at        TIMESTAMPTZ,
    submitted_by_name  VARCHAR(150),
    submitted_at       TIMESTAMPTZ,
    verified_by_name   VARCHAR(150),
    verified_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_competence_units UNIQUE (tenant_id, semester_id, module_id)
);

CREATE TABLE competence_unit_assessors (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants (id),
    unit_id   BIGINT NOT NULL REFERENCES competence_units (id) ON DELETE CASCADE,
    user_id   BIGINT NOT NULL REFERENCES user_accounts (id),
    CONSTRAINT uq_competence_unit_assessors UNIQUE (unit_id, user_id)
);

CREATE TABLE competence_tasks (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    unit_id            BIGINT        NOT NULL REFERENCES competence_units (id) ON DELETE CASCADE,
    name               VARCHAR(150)  NOT NULL,
    kind               VARCHAR(12)   NOT NULL CHECK (kind IN ('PRACTICAL', 'THEORY', 'WORKPLACE', 'TRADE_TEST', 'PROJECT')),
    evidence_required  VARCHAR(300),
    required           BOOLEAN       NOT NULL DEFAULT TRUE,
    sort_order         SMALLINT      NOT NULL DEFAULT 0
);
CREATE INDEX ix_competence_tasks_unit ON competence_tasks (unit_id);

CREATE TABLE competence_assessments (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT        NOT NULL REFERENCES tenants (id),
    task_id                 BIGINT        NOT NULL REFERENCES competence_tasks (id),
    module_registration_id  BIGINT        NOT NULL REFERENCES module_registrations (id),
    student_id              BIGINT        NOT NULL REFERENCES students (id),
    attempt                 SMALLINT      NOT NULL CHECK (attempt BETWEEN 1 AND 6),
    outcome                 VARCHAR(20)   NOT NULL CHECK (outcome IN ('COMPETENT', 'NOT_YET_COMPETENT')),
    practical_score         NUMERIC(5,2)  CHECK (practical_score BETWEEN 0 AND 100),
    theory_score            NUMERIC(5,2)  CHECK (theory_score BETWEEN 0 AND 100),
    assessed_on             DATE          NOT NULL,
    assessor_id             BIGINT        NOT NULL,
    assessor_name           VARCHAR(150)  NOT NULL,
    evidence_ref            VARCHAR(300)  NOT NULL,
    comment                 VARCHAR(500),
    verified                BOOLEAN       NOT NULL DEFAULT FALSE,
    recorded_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_competence_assessments UNIQUE (task_id, module_registration_id, attempt)
);
CREATE INDEX ix_competence_assessments_student ON competence_assessments (tenant_id, student_id);

-- The Examinations Officer's confirmation of award eligibility for a class
CREATE TABLE nva_award_lists (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    semester_id        BIGINT        NOT NULL REFERENCES semesters (id),
    programme_id       BIGINT        NOT NULL REFERENCES programmes (id),
    year_of_study      SMALLINT      NOT NULL,
    students           INT           NOT NULL,
    eligible           INT           NOT NULL,
    confirmed_by_name  VARCHAR(150)  NOT NULL,
    confirmed_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_nva_award_lists UNIQUE (tenant_id, semester_id, programme_id, year_of_study)
);

CREATE TABLE nva_award_list_lines (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    list_id           BIGINT         NOT NULL REFERENCES nva_award_lists (id) ON DELETE CASCADE,
    student_id        BIGINT         NOT NULL REFERENCES students (id),
    units_competent   INT            NOT NULL,
    units_required    INT            NOT NULL,
    eligible          BOOLEAN        NOT NULL,
    outstanding       VARCHAR(1000)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['competence_units', 'competence_unit_assessors', 'competence_tasks', 'competence_assessments',
                             'nva_award_lists', 'nva_award_list_lines']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
