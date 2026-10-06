-- =====================================================================
-- CIMS V6: selection (BP-03) — rounds proposed by the Admissions Office,
-- reviewed and approved by the Admissions Committee.
-- =====================================================================

CREATE TABLE selection_rounds (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenants (id),
    window_id          BIGINT       NOT NULL REFERENCES admission_windows (id),
    round_no           SMALLINT     NOT NULL,
    status             VARCHAR(12)  NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'CANCELLED')),
    proposed_at        TIMESTAMPTZ  NOT NULL,
    proposed_by        BIGINT       NOT NULL,
    proposed_by_name   VARCHAR(200) NOT NULL,
    approved_at        TIMESTAMPTZ,
    approved_by        BIGINT,
    approved_by_name   VARCHAR(200),
    committee_minute_ref VARCHAR(100),
    notes              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_selection_rounds UNIQUE (window_id, round_no)
);
-- At most one round awaiting committee approval per window
CREATE UNIQUE INDEX uq_selection_rounds_open ON selection_rounds (window_id) WHERE status = 'PROPOSED';

-- One outcome per application per round
CREATE TABLE selections (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id),
    round_id             BIGINT        NOT NULL REFERENCES selection_rounds (id) ON DELETE CASCADE,
    application_id       BIGINT        NOT NULL REFERENCES applications (id),
    window_programme_id  BIGINT        REFERENCES window_programmes (id),   -- programme placed in (null if not placed)
    choice_order         SMALLINT,                                           -- which of the applicant's choices was met
    merit_score          NUMERIC(6,2)  NOT NULL,
    rank_in_programme    INT,
    status               VARCHAR(12)   NOT NULL CHECK (status IN ('SELECTED', 'NOT_SELECTED', 'EXCLUDED')),
    exclusion_reason     VARCHAR(300),
    excluded_by_name     VARCHAR(200),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_selections UNIQUE (round_id, application_id),
    CONSTRAINT ck_selections_placed CHECK ((status = 'SELECTED') = (window_programme_id IS NOT NULL)),
    CONSTRAINT ck_selections_excluded CHECK ((status = 'EXCLUDED') = (exclusion_reason IS NOT NULL))
);
CREATE INDEX ix_selections_programme ON selections (round_id, window_programme_id);

-- The programme an applicant was finally selected for (set on committee approval)
ALTER TABLE applications ADD COLUMN selected_window_programme_id BIGINT REFERENCES window_programmes (id);
ALTER TABLE applications ADD COLUMN selection_round_id BIGINT REFERENCES selection_rounds (id);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['selection_rounds', 'selections']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
