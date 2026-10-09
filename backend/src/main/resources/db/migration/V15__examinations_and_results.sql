-- =====================================================================
-- CIMS V15: examination regulations (FR-ACA-05), module offerings and
-- assessment plans (FR-EXM-01), marks entry (FR-EXM-02/03), results
-- approval (FR-EXM-05, BP-08), GPA and progression (FR-EXM-06/07) and
-- publication (FR-EXM-10).
-- =====================================================================

-- Examination regulations per award level, versioned by the academic year they apply from.
CREATE TABLE regulation_sets (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id                BIGINT        NOT NULL REFERENCES tenants (id),
    name                     VARCHAR(120)  NOT NULL,
    award_family             VARCHAR(3)    NOT NULL CHECK (award_family IN ('NTA', 'NVA')),
    level_from               SMALLINT      NOT NULL CHECK (level_from BETWEEN 1 AND 8),
    level_to                 SMALLINT      NOT NULL CHECK (level_to BETWEEN 1 AND 8),
    effective_year_id        BIGINT        NOT NULL REFERENCES academic_years (id),
    ca_weight                NUMERIC(5,2)  NOT NULL,                  -- e.g. 40: coursework counts for 40 of 100
    se_weight                NUMERIC(5,2)  NOT NULL,                  -- e.g. 60: the semester examination for 60
    ca_pass_percent          NUMERIC(5,2)  NOT NULL DEFAULT 0,        -- share of the CA weight needed, e.g. 50 = 20 of 40
    se_pass_percent          NUMERIC(5,2)  NOT NULL DEFAULT 0,        -- share of the SE weight needed, e.g. 50 = 30 of 60
    gpa_decimals             SMALLINT      NOT NULL DEFAULT 1 CHECK (gpa_decimals BETWEEN 0 AND 3),
    gpa_rounding             VARCHAR(8)    NOT NULL DEFAULT 'ROUND' CHECK (gpa_rounding IN ('ROUND', 'TRUNCATE')),
    supp_max_failed_percent  NUMERIC(5,2)  NOT NULL DEFAULT 50,       -- supplementary when fewer than this share of modules failed
    supp_max_grade           VARCHAR(3),                              -- highest grade after a supplementary (applied in the next slice)
    discontinue_gpa_below    NUMERIC(3,1),                            -- above the supplementary limit: discontinue below this GPA, else repeat
    notes                    VARCHAR(1000),
    created_by_name          VARCHAR(150),
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_regulation_levels CHECK (level_to >= level_from),
    CONSTRAINT ck_regulation_weights CHECK (ca_weight >= 0 AND se_weight >= 0 AND ca_weight + se_weight = 100),
    CONSTRAINT uq_regulation_sets UNIQUE (tenant_id, award_family, level_from, level_to, effective_year_id)
);

CREATE TABLE grade_bands (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    regulation_set_id  BIGINT        NOT NULL REFERENCES regulation_sets (id) ON DELETE CASCADE,
    grade              VARCHAR(3)    NOT NULL,
    min_mark           SMALLINT      NOT NULL CHECK (min_mark BETWEEN 0 AND 100),
    max_mark           SMALLINT      NOT NULL CHECK (max_mark BETWEEN 0 AND 100),
    points             NUMERIC(3,1)  NOT NULL CHECK (points >= 0),
    remark             VARCHAR(40),
    passing            BOOLEAN       NOT NULL,
    CONSTRAINT ck_grade_band_range CHECK (max_mark >= min_mark),
    CONSTRAINT uq_grade_bands UNIQUE (regulation_set_id, grade)
);

CREATE TABLE classification_bands (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    regulation_set_id  BIGINT        NOT NULL REFERENCES regulation_sets (id) ON DELETE CASCADE,
    name               VARCHAR(40)   NOT NULL,
    min_gpa            NUMERIC(3,1)  NOT NULL,
    max_gpa            NUMERIC(3,1)  NOT NULL,
    CONSTRAINT ck_classification_range CHECK (max_gpa >= min_gpa)
);

-- Institution choices for the results workflow (BR-17)
CREATE TABLE exam_settings (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT       NOT NULL REFERENCES tenants (id),
    use_department_board    BOOLEAN      NOT NULL DEFAULT TRUE,     -- Head of Department → Department Board → ...
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_exam_settings UNIQUE (tenant_id)
);

-- When lecturers may enter semester-examination marks, and when all marks entry closes
ALTER TABLE semesters ADD COLUMN marks_entry_opens DATE;
ALTER TABLE semesters ADD COLUMN marks_entry_closes DATE;

-- A module taught in a semester, its lecturers and its results through approval (BP-08 steps 1-5)
CREATE TABLE module_offerings (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL REFERENCES tenants (id),
    semester_id         BIGINT        NOT NULL REFERENCES semesters (id),
    module_id           BIGINT        NOT NULL REFERENCES modules (id),
    regulation_set_id   BIGINT        NOT NULL REFERENCES regulation_sets (id),
    status              VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT', 'SUBMITTED', 'HOD_APPROVED', 'BOARD_APPROVED')),
    returned_note       VARCHAR(1000),
    returned_by_name    VARCHAR(150),
    returned_at         TIMESTAMPTZ,
    submitted_by_name   VARCHAR(150),
    submitted_at        TIMESTAMPTZ,
    hod_by_name         VARCHAR(150),
    hod_at              TIMESTAMPTZ,
    board_by_name       VARCHAR(150),
    board_at            TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_module_offerings UNIQUE (tenant_id, semester_id, module_id)
);

CREATE TABLE offering_staff (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT  NOT NULL REFERENCES tenants (id),
    offering_id  BIGINT  NOT NULL REFERENCES module_offerings (id) ON DELETE CASCADE,
    user_id      BIGINT  NOT NULL REFERENCES user_accounts (id),
    CONSTRAINT uq_offering_staff UNIQUE (offering_id, user_id)
);
CREATE INDEX ix_offering_staff_user ON offering_staff (tenant_id, user_id);

-- The assessment plan: CA components (tests, assignments, practicals) and the semester examination
CREATE TABLE assessment_components (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES tenants (id),
    offering_id  BIGINT        NOT NULL REFERENCES module_offerings (id) ON DELETE CASCADE,
    name         VARCHAR(80)   NOT NULL,
    category     VARCHAR(2)    NOT NULL CHECK (category IN ('CA', 'SE')),
    weight       NUMERIC(5,2)  NOT NULL CHECK (weight > 0 AND weight <= 100),
    max_score    NUMERIC(6,2)  NOT NULL DEFAULT 100 CHECK (max_score > 0),
    sort_order   SMALLINT      NOT NULL DEFAULT 0
);

CREATE TABLE assessment_scores (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT        NOT NULL REFERENCES tenants (id),
    component_id            BIGINT        NOT NULL REFERENCES assessment_components (id) ON DELETE CASCADE,
    module_registration_id  BIGINT        NOT NULL REFERENCES module_registrations (id),
    score                   NUMERIC(6,2)  CHECK (score >= 0),
    absent                  BOOLEAN       NOT NULL DEFAULT FALSE,
    entered_by_name         VARCHAR(150),
    entered_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_assessment_scores UNIQUE (component_id, module_registration_id),
    CONSTRAINT ck_assessment_score_or_absent CHECK (NOT (absent AND score IS NOT NULL))
);

-- The computed result of a module registration (one attempt)
CREATE TABLE module_results (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT        NOT NULL REFERENCES tenants (id),
    module_registration_id  BIGINT        NOT NULL REFERENCES module_registrations (id),
    offering_id             BIGINT        NOT NULL REFERENCES module_offerings (id),
    student_id              BIGINT        NOT NULL REFERENCES students (id),
    regulation_set_id       BIGINT        NOT NULL REFERENCES regulation_sets (id),
    ca_total                NUMERIC(5,2)  CHECK (ca_total BETWEEN 0 AND 100),
    se_total                NUMERIC(5,2)  CHECK (se_total BETWEEN 0 AND 100),
    total                   NUMERIC(5,2)  CHECK (total BETWEEN 0 AND 100),
    grade                   VARCHAR(3),
    grade_points            NUMERIC(3,1),
    credits                 NUMERIC(4,1)  NOT NULL,
    remark                  VARCHAR(20)   NOT NULL CHECK (remark IN ('PASS', 'FAIL', 'INCOMPLETE', 'ABSENT')),
    fail_reason             VARCHAR(80),
    capped                  BOOLEAN       NOT NULL DEFAULT FALSE,
    locked                  BOOLEAN       NOT NULL DEFAULT FALSE,
    computed_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_module_results UNIQUE (module_registration_id)
);
CREATE INDEX ix_module_results_offering ON module_results (tenant_id, offering_id);
CREATE INDEX ix_module_results_student ON module_results (tenant_id, student_id);

-- The class result sheet of a programme and year for a semester (BP-08 steps 6-9)
CREATE TABLE result_sheets (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES tenants (id),
    semester_id           BIGINT        NOT NULL REFERENCES semesters (id),
    programme_id          BIGINT        NOT NULL REFERENCES programmes (id),
    year_of_study         SMALLINT      NOT NULL,
    regulation_set_id     BIGINT        NOT NULL REFERENCES regulation_sets (id),
    status                VARCHAR(12)   NOT NULL DEFAULT 'COMPUTED'
                          CHECK (status IN ('COMPUTED', 'RECOMMENDED', 'CONFIRMED', 'PUBLISHED')),
    computed_by_name      VARCHAR(150),
    computed_at           TIMESTAMPTZ,
    recommended_by        BIGINT,
    recommended_by_name   VARCHAR(150),
    recommended_at        TIMESTAMPTZ,
    committee_note        VARCHAR(1000),
    confirmed_by_name     VARCHAR(150),
    confirmed_at          TIMESTAMPTZ,
    published_by_name     VARCHAR(150),
    published_at          TIMESTAMPTZ,
    returned_note         VARCHAR(1000),
    returned_by_name      VARCHAR(150),
    returned_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_result_sheets UNIQUE (tenant_id, semester_id, programme_id, year_of_study)
);

CREATE TABLE semester_results (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id),
    result_sheet_id      BIGINT        NOT NULL REFERENCES result_sheets (id) ON DELETE CASCADE,
    student_id           BIGINT        NOT NULL REFERENCES students (id),
    credits_registered   NUMERIC(5,1)  NOT NULL,
    credits_passed       NUMERIC(5,1)  NOT NULL,
    credits_graded       NUMERIC(5,1)  NOT NULL,                     -- credits counted in the GPA
    points_total         NUMERIC(7,2)  NOT NULL,                     -- sum of grade points x credits
    gpa                  NUMERIC(4,2),
    cgpa                 NUMERIC(4,2),
    modules_failed       SMALLINT      NOT NULL DEFAULT 0,
    modules_incomplete   SMALLINT      NOT NULL DEFAULT 0,
    proposed_decision    VARCHAR(16)   NOT NULL,
    decision             VARCHAR(16)   NOT NULL
                         CHECK (decision IN ('PASS', 'SUPPLEMENTARY', 'SPECIAL_EXAM', 'INCOMPLETE', 'REPEAT', 'DISCONTINUE')),
    decision_reason      VARCHAR(500),
    classification       VARCHAR(40),
    locked               BOOLEAN       NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_semester_results UNIQUE (result_sheet_id, student_id)
);
CREATE INDEX ix_semester_results_student ON semester_results (tenant_id, student_id);

-- Confirmed results cannot be changed except through the amendment procedure (BRU-13)
CREATE FUNCTION cims_protect_locked_results() RETURNS trigger AS $$
BEGIN
    IF OLD.locked AND coalesce(current_setting('cims.results_amendment', true), '') <> 'on' THEN
        RAISE EXCEPTION 'Confirmed results are locked (%.%)', TG_TABLE_NAME, OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER tr_module_results_locked BEFORE UPDATE OR DELETE ON module_results
    FOR EACH ROW EXECUTE FUNCTION cims_protect_locked_results();
CREATE TRIGGER tr_semester_results_locked BEFORE UPDATE OR DELETE ON semester_results
    FOR EACH ROW EXECUTE FUNCTION cims_protect_locked_results();

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['regulation_sets', 'grade_bands', 'classification_bands', 'exam_settings', 'module_offerings', 'offering_staff',
                             'assessment_components', 'assessment_scores', 'module_results', 'result_sheets', 'semester_results']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
