-- BR-13 / FR-ACA-01 / FR-REG-05: campuses, optional faculties (schools), where programmes are offered, and programme and
-- campus transfers with history. BR-22 / FR-FIN-01: fee structures also by intake and student category.

CREATE TABLE campuses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    region      VARCHAR(60),
    district    VARCHAR(80),
    address     VARCHAR(250),
    main        BOOLEAN      NOT NULL DEFAULT false,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_campuses UNIQUE (tenant_id, code)
);
-- one main campus per institution
CREATE UNIQUE INDEX uq_campuses_main ON campuses (tenant_id) WHERE main;

CREATE TABLE faculties (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_faculties UNIQUE (tenant_id, code)
);

ALTER TABLE departments ADD COLUMN faculty_id BIGINT REFERENCES faculties (id);

-- Campuses a programme is taught at; none listed = the main campus.
CREATE TABLE programme_campuses (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT NOT NULL REFERENCES tenants (id),
    programme_id  BIGINT NOT NULL REFERENCES programmes (id) ON DELETE CASCADE,
    campus_id     BIGINT NOT NULL REFERENCES campuses (id),
    CONSTRAINT uq_programme_campuses UNIQUE (programme_id, campus_id)
);

-- Student categories priced separately (e.g. regular, evening, weekend, distance); none = one price for all.
CREATE TABLE student_categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_student_categories UNIQUE (tenant_id, code)
);

ALTER TABLE students ADD COLUMN campus_id BIGINT REFERENCES campuses (id);
ALTER TABLE students ADD COLUMN student_category_id BIGINT REFERENCES student_categories (id);

-- Fee structures: optional intake and category; a structure without them applies to every intake / category.
ALTER TABLE fee_structures ADD COLUMN intake_id BIGINT REFERENCES intakes (id);
ALTER TABLE fee_structures ADD COLUMN student_category_id BIGINT REFERENCES student_categories (id);
ALTER TABLE fee_structures DROP CONSTRAINT uq_fee_structures;
DROP INDEX uq_fee_structures_approved;
DROP INDEX uq_fee_structures_draft;
CREATE UNIQUE INDEX uq_fee_structures ON fee_structures
    (academic_year_id, programme_id, year_of_study, COALESCE(intake_id, 0), COALESCE(student_category_id, 0), structure_version);
CREATE UNIQUE INDEX uq_fee_structures_approved ON fee_structures
    (academic_year_id, programme_id, year_of_study, COALESCE(intake_id, 0), COALESCE(student_category_id, 0)) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX uq_fee_structures_draft ON fee_structures
    (academic_year_id, programme_id, year_of_study, COALESCE(intake_id, 0), COALESCE(student_category_id, 0)) WHERE status = 'DRAFT';

-- Programme and campus transfers (FR-REG-05). The registration number never changes; history is kept here and in the audit trail.
CREATE TABLE student_transfers (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES tenants (id),
    student_id            BIGINT        NOT NULL REFERENCES students (id),
    kind                  VARCHAR(10)   NOT NULL CHECK (kind IN ('PROGRAMME', 'CAMPUS', 'BOTH')),
    from_programme_id     BIGINT        NOT NULL REFERENCES programmes (id),
    to_programme_id       BIGINT        NOT NULL REFERENCES programmes (id),
    from_campus_id        BIGINT        REFERENCES campuses (id),
    to_campus_id          BIGINT        REFERENCES campuses (id),
    from_year_of_study    SMALLINT      NOT NULL,
    to_year_of_study      SMALLINT      NOT NULL CHECK (to_year_of_study BETWEEN 1 AND 8),
    reason                VARCHAR(1000) NOT NULL,
    effective_date        DATE          NOT NULL,
    state                 VARCHAR(12)   NOT NULL DEFAULT 'REQUESTED' CHECK (state IN ('REQUESTED', 'RECOMMENDED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by_student  BOOLEAN       NOT NULL DEFAULT false,
    requested_by_id       BIGINT,
    requested_by_name     VARCHAR(200)  NOT NULL,
    requested_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    recommended_by_name   VARCHAR(200),
    recommended_at        TIMESTAMPTZ,
    recommendation_note   VARCHAR(1000),
    decided_by_id         BIGINT,
    decided_by_name       VARCHAR(200),
    decided_at            TIMESTAMPTZ,
    decision_note         VARCHAR(1000),
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_student_transfers_student ON student_transfers (tenant_id, student_id);
-- one open transfer per student
CREATE UNIQUE INDEX uq_student_transfers_open ON student_transfers (student_id) WHERE state IN ('REQUESTED', 'RECOMMENDED');

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['campuses', 'faculties', 'programme_campuses', 'student_categories', 'student_transfers']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
