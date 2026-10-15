-- BR-39 / FR-ADM-S07: migration of existing students, results and balances from files, validated first, applied only after
-- another person signs off. BR-08 / FR-ADM-15: students selected by external bodies (e.g. TAMISEMI) imported into a window.

CREATE TABLE import_batches (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    batch_no          VARCHAR(40)   NOT NULL,
    kind              VARCHAR(12)   NOT NULL CHECK (kind IN ('STUDENTS', 'RESULTS', 'BALANCES', 'SELECTIONS')),
    file_name         VARCHAR(200)  NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'VALIDATED' CHECK (status IN ('VALIDATED', 'APPLIED', 'DISCARDED')),
    total_rows        INT           NOT NULL,
    error_rows        INT           NOT NULL,
    rows              JSONB         NOT NULL,     -- the rows as read (checked again at sign-off)
    errors            JSONB         NOT NULL,     -- [{row, field, message}]
    notes             JSONB         NOT NULL DEFAULT '[]'::jsonb,   -- things that will happen, e.g. academic years to be created
    window_id         BIGINT        REFERENCES admission_windows (id),
    external_body     VARCHAR(40),
    uploaded_by_id    BIGINT,
    uploaded_by_name  VARCHAR(200)  NOT NULL,
    uploaded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    decided_by_name   VARCHAR(200),
    decided_at        TIMESTAMPTZ,
    sign_off_note     VARCHAR(500),
    summary           JSONB,
    CONSTRAINT uq_import_batches UNIQUE (tenant_id, batch_no),
    CONSTRAINT ck_import_batches_window CHECK (kind <> 'SELECTIONS' OR (window_id IS NOT NULL AND external_body IS NOT NULL))
);

-- Results from before CIMS, as the old system or the records office kept them.
CREATE TABLE legacy_semester_results (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    student_id       BIGINT        NOT NULL REFERENCES students (id),
    academic_year    VARCHAR(9)    NOT NULL,
    semester_no      SMALLINT      NOT NULL CHECK (semester_no BETWEEN 1 AND 3),
    year_of_study    SMALLINT      NOT NULL CHECK (year_of_study BETWEEN 1 AND 8),
    gpa              NUMERIC(4,2),
    cgpa             NUMERIC(4,2),
    decision         VARCHAR(40),
    batch_id         BIGINT        NOT NULL REFERENCES import_batches (id),
    CONSTRAINT uq_legacy_semester_results UNIQUE (student_id, academic_year, semester_no)
);
CREATE TABLE legacy_module_results (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL REFERENCES tenants (id),
    semester_result_id  BIGINT        NOT NULL REFERENCES legacy_semester_results (id) ON DELETE CASCADE,
    module_code         VARCHAR(20)   NOT NULL,
    module_title        VARCHAR(200)  NOT NULL,
    credits             NUMERIC(5,1)  NOT NULL CHECK (credits > 0),
    marks               NUMERIC(5,1)  CHECK (marks BETWEEN 0 AND 100),
    grade               VARCHAR(3)    NOT NULL,
    points              NUMERIC(4,2),
    CONSTRAINT uq_legacy_module_results UNIQUE (semester_result_id, module_code)
);
CREATE INDEX ix_legacy_semester_results_student ON legacy_semester_results (tenant_id, student_id);

ALTER TABLE students ADD COLUMN import_batch_id BIGINT REFERENCES import_batches (id);

ALTER TABLE applications ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PORTAL' CHECK (source IN ('PORTAL', 'EXTERNAL'));
ALTER TABLE applications ADD COLUMN external_body VARCHAR(40);
ALTER TABLE applications ADD COLUMN external_ref VARCHAR(60);
ALTER TABLE applications ADD COLUMN import_batch_id BIGINT REFERENCES import_batches (id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['import_batches', 'legacy_semester_results', 'legacy_module_results']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
