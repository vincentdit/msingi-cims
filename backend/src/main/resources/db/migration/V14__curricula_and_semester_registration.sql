-- =====================================================================
-- CIMS V14: academic calendar (FR-ACA-04), module catalogue and versioned
-- curricula (FR-ACA-02/03), semester registration (FR-REG-03, BP-05).
-- =====================================================================

CREATE TABLE semesters (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES tenants (id),
    academic_year_id         BIGINT       NOT NULL REFERENCES academic_years (id),
    semester_no              SMALLINT     NOT NULL CHECK (semester_no BETWEEN 1 AND 3),
    name                     VARCHAR(60)  NOT NULL,
    start_date               DATE         NOT NULL,
    end_date                 DATE         NOT NULL,
    registration_opens       DATE         NOT NULL,
    registration_closes      DATE         NOT NULL,
    late_registration_closes DATE,                               -- after the deadline, until this date, registration is marked late
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_semesters UNIQUE (tenant_id, academic_year_id, semester_no),
    CONSTRAINT ck_semesters_dates CHECK (end_date > start_date AND registration_closes >= registration_opens
        AND (late_registration_closes IS NULL OR late_registration_closes >= registration_closes))
);

-- Key dates of the academic calendar
CREATE TABLE calendar_events (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id  BIGINT        NOT NULL REFERENCES academic_years (id),
    semester_id       BIGINT        REFERENCES semesters (id),
    kind              VARCHAR(20)   NOT NULL CHECK (kind IN ('REGISTRATION', 'TEACHING', 'MARKS_ENTRY', 'EXAMINATIONS', 'RESULTS', 'HOLIDAY',
                                                            'GRADUATION', 'OTHER')),
    title             VARCHAR(150)  NOT NULL,
    start_date        DATE          NOT NULL,
    end_date          DATE          NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_calendar_events_dates CHECK (end_date >= start_date)
);
CREATE INDEX ix_calendar_events_year ON calendar_events (tenant_id, academic_year_id, start_date);

-- Module (unit) catalogue; one module can be in several programmes' curricula (FR-ACA-03)
CREATE TABLE modules (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    code             VARCHAR(20)   NOT NULL,
    title            VARCHAR(200)  NOT NULL,
    credits          NUMERIC(4,1)  NOT NULL CHECK (credits > 0 AND credits <= 60),
    nta_level        SMALLINT      CHECK (nta_level BETWEEN 1 AND 8),
    assessment_type  VARCHAR(12)   NOT NULL CHECK (assessment_type IN ('GRADED', 'COMPETENCE')),
    department_id    BIGINT        REFERENCES departments (id),
    description      VARCHAR(1000),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_modules_code UNIQUE (tenant_id, code)
);

-- Versioned curriculum per programme; approved by the Deputy Principal (Academic), then fixed
CREATE TABLE curricula (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT        NOT NULL REFERENCES tenants (id),
    programme_id            BIGINT        NOT NULL REFERENCES programmes (id),
    curriculum_version      SMALLINT      NOT NULL,
    effective_year_id       BIGINT        NOT NULL REFERENCES academic_years (id),   -- for students entering from this year
    status                  VARCHAR(10)   NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'RETIRED')),
    notes                   VARCHAR(1000),
    created_by              BIGINT,
    created_by_name         VARCHAR(200)  NOT NULL,
    approved_at             TIMESTAMPTZ,
    approved_by_name        VARCHAR(200),
    retired_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                 BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_curricula_version UNIQUE (programme_id, curriculum_version),
    CONSTRAINT ck_curricula_approved CHECK (status = 'DRAFT' OR approved_at IS NOT NULL)
);
CREATE UNIQUE INDEX uq_curricula_draft ON curricula (programme_id) WHERE status = 'DRAFT';

CREATE TABLE curriculum_modules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    curriculum_id   BIGINT        NOT NULL REFERENCES curricula (id) ON DELETE CASCADE,
    module_id       BIGINT        NOT NULL REFERENCES modules (id),
    year_of_study   SMALLINT      NOT NULL CHECK (year_of_study BETWEEN 1 AND 8),
    semester_no     SMALLINT      NOT NULL CHECK (semester_no BETWEEN 1 AND 3),
    category        VARCHAR(8)    NOT NULL CHECK (category IN ('CORE', 'ELECTIVE')),
    CONSTRAINT uq_curriculum_modules UNIQUE (curriculum_id, module_id)
);

-- How many electives a student takes in a year/semester of a curriculum
CREATE TABLE curriculum_elective_rules (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    curriculum_id   BIGINT        NOT NULL REFERENCES curricula (id) ON DELETE CASCADE,
    year_of_study   SMALLINT      NOT NULL,
    semester_no     SMALLINT      NOT NULL,
    min_electives   SMALLINT      NOT NULL CHECK (min_electives >= 0),
    max_electives   SMALLINT      NOT NULL CHECK (max_electives >= min_electives),
    CONSTRAINT uq_curriculum_elective_rules UNIQUE (curriculum_id, year_of_study, semester_no)
);

-- A student keeps the curriculum version they started on
ALTER TABLE students ADD COLUMN curriculum_id BIGINT REFERENCES curricula (id);

CREATE TABLE semester_registrations (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    semester_id        BIGINT        NOT NULL REFERENCES semesters (id),
    curriculum_id      BIGINT        NOT NULL REFERENCES curricula (id),
    year_of_study      SMALLINT      NOT NULL,
    status             VARCHAR(10)   NOT NULL CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    late               BOOLEAN       NOT NULL DEFAULT FALSE,
    total_credits      NUMERIC(5,1)  NOT NULL,
    clearance          JSONB         NOT NULL,       -- the fee clearance as it was when submitted/approved
    details_confirmed  BOOLEAN       NOT NULL,
    submitted_at       TIMESTAMPTZ   NOT NULL,
    submitted_by_name  VARCHAR(200)  NOT NULL,
    decided_at         TIMESTAMPTZ,
    decided_by_name    VARCHAR(200),
    decision_note      VARCHAR(500),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_semester_registrations UNIQUE (student_id, semester_id)
);
CREATE INDEX ix_semester_registrations_semester ON semester_registrations (tenant_id, semester_id, status);

CREATE TABLE module_registrations (
    id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                 BIGINT        NOT NULL REFERENCES tenants (id),
    semester_registration_id  BIGINT        NOT NULL REFERENCES semester_registrations (id) ON DELETE CASCADE,
    student_id                BIGINT        NOT NULL REFERENCES students (id),
    module_id                 BIGINT        NOT NULL REFERENCES modules (id),
    category                  VARCHAR(8)    NOT NULL CHECK (category IN ('CORE', 'ELECTIVE')),
    attempt                   VARCHAR(12)   NOT NULL DEFAULT 'FIRST' CHECK (attempt IN ('FIRST', 'SUPPLEMENTARY', 'CARRY_OVER', 'REPEAT')),
    credits                   NUMERIC(4,1)  NOT NULL,
    CONSTRAINT uq_module_registrations UNIQUE (semester_registration_id, module_id)
);
CREATE INDEX ix_module_registrations_module ON module_registrations (tenant_id, module_id);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['semesters', 'calendar_events', 'modules', 'curricula', 'curriculum_modules', 'curriculum_elective_rules',
                             'semester_registrations', 'module_registrations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
