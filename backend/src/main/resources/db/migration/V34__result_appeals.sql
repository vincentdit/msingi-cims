-- =====================================================================
-- CIMS V34: remarking and appeals (FR-EXM-12, BP-11).
-- After results are published a student may ask, within the appeal
-- period and for a fee per module, for an examination script to be
-- re-marked or appeal a result. The Examinations Officer assigns an
-- examiner who did not teach the module; the examiner records the new
-- marks (the original marks are kept); a changed result goes through
-- the amendment approval, and the student is told the outcome.
-- =====================================================================

ALTER TABLE exam_settings
    ADD COLUMN appeal_window_days  SMALLINT      NOT NULL DEFAULT 14 CHECK (appeal_window_days BETWEEN 0 AND 120),
    ADD COLUMN appeal_fee          NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (appeal_fee >= 0);

CREATE TABLE result_appeals (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT        NOT NULL REFERENCES tenants (id),
    student_id          BIGINT        NOT NULL REFERENCES students (id),
    module_result_id    BIGINT        NOT NULL REFERENCES module_results (id),
    offering_id         BIGINT        NOT NULL REFERENCES module_offerings (id),
    kind                VARCHAR(8)    NOT NULL CHECK (kind IN ('REMARK', 'APPEAL')),
    reason              VARCHAR(1000) NOT NULL,
    fee                 NUMERIC(12,2) NOT NULL DEFAULT 0,
    invoice_id          BIGINT        REFERENCES invoices (id),
    status              VARCHAR(18)   NOT NULL DEFAULT 'REQUESTED'
                        CHECK (status IN ('REQUESTED', 'ASSIGNED', 'MARKED', 'AMENDMENT_PENDING', 'CLOSED', 'REJECTED', 'WITHDRAWN')),
    outcome             VARCHAR(10)   CHECK (outcome IN ('CHANGED', 'UNCHANGED')),
    original_ca_total   NUMERIC(5,2),
    original_se_total   NUMERIC(5,2),
    original_total      NUMERIC(5,2),
    original_grade      VARCHAR(3),
    examiner_user_id    BIGINT        REFERENCES user_accounts (id),
    examiner_name       VARCHAR(150),
    assigned_by_name    VARCHAR(150),
    assigned_at         TIMESTAMPTZ,
    new_ca_total        NUMERIC(5,2),
    new_se_total        NUMERIC(5,2),
    new_total           NUMERIC(5,2),
    new_grade           VARCHAR(3),
    examiner_comment    VARCHAR(1000),
    marked_at           TIMESTAMPTZ,
    amendment_id        BIGINT        REFERENCES result_amendments (id),
    decision_note       VARCHAR(1000),
    decided_by_name     VARCHAR(150),
    closed_at           TIMESTAMPTZ,
    requested_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version             BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_result_appeals_student ON result_appeals (tenant_id, student_id);
CREATE INDEX ix_result_appeals_status ON result_appeals (tenant_id, status);
CREATE UNIQUE INDEX uq_result_appeals_open ON result_appeals (module_result_id)
    WHERE status IN ('REQUESTED', 'ASSIGNED', 'MARKED', 'AMENDMENT_PENDING');

ALTER TABLE result_appeals ENABLE ROW LEVEL SECURITY;
ALTER TABLE result_appeals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON result_appeals USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());
