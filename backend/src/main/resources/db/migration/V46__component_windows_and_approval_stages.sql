-- FR-EXM-02: marks entry opened and closed per assessment item (e.g. Test 1 until a date), besides the semester window.
-- FR-EXM-05 / FR-ADM-S03: the approval stages results pass through, set by the institution.

ALTER TABLE assessment_components ADD COLUMN entry_opens  DATE;
ALTER TABLE assessment_components ADD COLUMN entry_closes DATE;
ALTER TABLE assessment_components ADD CONSTRAINT ck_assessment_components_window CHECK (entry_closes IS NULL OR entry_opens IS NULL OR entry_closes >= entry_opens);

-- MODULE_RESULTS: stages after the Head of Department and before the module's results count as approved by the department.
-- RESULT_SHEET: stages after computation and before the Academic Committee recommends the class result sheet.
CREATE TABLE approval_stages (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT        NOT NULL REFERENCES tenants (id),
    flow         VARCHAR(16)   NOT NULL CHECK (flow IN ('MODULE_RESULTS', 'RESULT_SHEET')),
    stage_order  SMALLINT      NOT NULL CHECK (stage_order BETWEEN 1 AND 6),
    name         VARCHAR(100)  NOT NULL,
    role         VARCHAR(40)   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_approval_stages UNIQUE (tenant_id, flow, stage_order)
);

-- the institution has said how its module results are approved (otherwise the Department Board setting applies)
ALTER TABLE exam_settings ADD COLUMN module_stages_configured BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE module_offerings ADD COLUMN stage_no SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE result_sheets ADD COLUMN stage_no SMALLINT NOT NULL DEFAULT 0;

CREATE TABLE result_stage_approvals (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    flow              VARCHAR(16)   NOT NULL,
    subject_id        BIGINT        NOT NULL,       -- module offering or result sheet
    stage_order       SMALLINT      NOT NULL,
    stage_name        VARCHAR(100)  NOT NULL,
    approved_by_id    BIGINT,
    approved_by_name  VARCHAR(200)  NOT NULL,
    approved_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note              VARCHAR(1000)
);
CREATE INDEX ix_result_stage_approvals ON result_stage_approvals (tenant_id, flow, subject_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['approval_stages', 'result_stage_approvals']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
