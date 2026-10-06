-- =====================================================================
-- CIMS V9: registration hand-over (BP-05 step 1, FR-REG-01/02) and
-- first registration at reporting (BP-05 steps 4–5).
--
-- One student per applicant; one registration number per student, never
-- changed and never reused (BRU-06); students are never deleted.
-- =====================================================================

-- Institution's registration number pattern, e.g. {INST}/{FAMILY}{LEVEL}/{YY}/{SEQ:4}
ALTER TABLE tenants ADD COLUMN registration_no_pattern VARCHAR(80);

CREATE TABLE students (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT       NOT NULL REFERENCES tenants (id),
    applicant_id             BIGINT       REFERENCES applicants (id),      -- null only for migrated legacy students
    application_id           BIGINT       REFERENCES applications (id),
    user_id                  BIGINT       REFERENCES user_accounts (id),    -- portal sign-in (the applicant account)
    registration_no          VARCHAR(30)  NOT NULL,
    first_name               VARCHAR(60)  NOT NULL,
    middle_name              VARCHAR(60),
    surname                  VARCHAR(60)  NOT NULL,
    sex                      CHAR(1)      NOT NULL CHECK (sex IN ('M', 'F')),
    date_of_birth            DATE         NOT NULL,
    nationality              VARCHAR(60)  NOT NULL,
    phone                    VARCHAR(20)  NOT NULL,
    email                    VARCHAR(120),
    region                   VARCHAR(60),
    district                 VARCHAR(60),
    postal_address           VARCHAR(200),
    disability               VARCHAR(120),
    next_of_kin_name         VARCHAR(150),
    next_of_kin_phone        VARCHAR(20),
    next_of_kin_relation     VARCHAR(40),
    programme_id             BIGINT       NOT NULL REFERENCES programmes (id),
    entry_academic_year_id   BIGINT       NOT NULL REFERENCES academic_years (id),
    intake_id                BIGINT       NOT NULL REFERENCES intakes (id),
    year_of_study            SMALLINT     NOT NULL DEFAULT 1 CHECK (year_of_study BETWEEN 1 AND 8),
    nactvet_verification_no  VARCHAR(40),
    admission_letter_no      VARCHAR(40),
    status                   VARCHAR(16)  NOT NULL CHECK (status IN ('ADMITTED', 'ACTIVE', 'POSTPONED', 'SUSPENDED',
                                 'DISCONTINUED', 'WITHDRAWN', 'DECEASED', 'COMPLETED', 'GRADUATED')),
    admitted_at              TIMESTAMPTZ  NOT NULL,
    admitted_by_name         VARCHAR(200) NOT NULL,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_students_registration_no UNIQUE (tenant_id, registration_no),
    CONSTRAINT uq_students_applicant UNIQUE (tenant_id, applicant_id),
    CONSTRAINT uq_students_application UNIQUE (application_id)
);
CREATE INDEX ix_students_programme ON students (tenant_id, programme_id, status);
CREATE INDEX ix_students_cohort ON students (tenant_id, entry_academic_year_id, intake_id);

-- BRU-06: the registration number never changes, and students are never deleted
CREATE OR REPLACE FUNCTION cims_students_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'students are never deleted; record a status change instead';
    END IF;
    IF NEW.registration_no IS DISTINCT FROM OLD.registration_no THEN
        RAISE EXCEPTION 'a registration number never changes';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_students_guard BEFORE UPDATE OR DELETE ON students
    FOR EACH ROW EXECUTE FUNCTION cims_students_guard();

-- First registration when the student reports: original documents checked, then Registry approval
CREATE TABLE first_registrations (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants (id),
    student_id          BIGINT       NOT NULL UNIQUE REFERENCES students (id),
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('PENDING', 'APPROVED')),
    documents           JSONB        NOT NULL,          -- [{ "item": "...", "verified": true }] from the joining instructions
    reported_at         TIMESTAMPTZ,
    remarks             VARCHAR(500),
    checked_by_name     VARCHAR(200),
    approved_at         TIMESTAMPTZ,
    approved_by         BIGINT,
    approved_by_name    VARCHAR(200),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_first_registrations_approved CHECK ((status = 'APPROVED') = (approved_at IS NOT NULL))
);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['students', 'first_registrations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
