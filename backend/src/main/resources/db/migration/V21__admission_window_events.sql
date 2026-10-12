-- =====================================================================
-- CIMS V21: opening and closing admission windows (BP-01, FR-ADM-01).
-- The admissions office and the administrator open a window now or on
-- its date, close it early with a reason, reopen it with a new closing
-- date before selection starts, and extend it; windows close by
-- themselves when the closing date passes. Every change is kept here.
-- =====================================================================

CREATE TABLE admission_window_events (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    window_id          BIGINT        NOT NULL REFERENCES admission_windows (id) ON DELETE CASCADE,
    action             VARCHAR(12)   NOT NULL
                       CHECK (action IN ('CREATED', 'PUBLISHED', 'OPENED', 'CLOSED', 'REOPENED', 'EXTENDED', 'RESCHEDULED')),
    reason             VARCHAR(500),
    opens_at           TIMESTAMPTZ,
    closes_at          TIMESTAMPTZ,
    previous_closes_at TIMESTAMPTZ,
    applicants_notified INTEGER      NOT NULL DEFAULT 0,
    automatic          BOOLEAN       NOT NULL DEFAULT FALSE,
    by_name            VARCHAR(150)  NOT NULL,
    at                 TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_admission_window_events_window ON admission_window_events (tenant_id, window_id, at);

ALTER TABLE admission_window_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_window_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON admission_window_events
    USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());
