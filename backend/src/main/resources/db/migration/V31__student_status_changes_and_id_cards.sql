-- =====================================================================
-- CIMS V31: Registry (FR-REG-04, FR-REG-06, BP-14).
-- Student status changes (postponement, resumption, suspension,
-- discontinuation, withdrawal, death) requested by Registry with a
-- reason and effective date, recommended by the Head of Department,
-- approved by the Deputy Principal (Academic) and applied on the
-- effective date; students' photos; and student ID cards with a QR
-- verification code, reissued when lost or damaged.
-- =====================================================================

CREATE TABLE student_status_changes (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT        NOT NULL REFERENCES tenants (id),
    student_id           BIGINT        NOT NULL REFERENCES students (id),
    change_type          VARCHAR(16)   NOT NULL
                         CHECK (change_type IN ('POSTPONEMENT', 'RESUMPTION', 'SUSPENSION', 'DISCONTINUATION', 'WITHDRAWAL', 'DEATH')),
    from_status          VARCHAR(16)   NOT NULL,
    to_status            VARCHAR(16)   NOT NULL,
    reason               VARCHAR(1000) NOT NULL,
    reference            VARCHAR(120),
    effective_date       DATE          NOT NULL,
    until_date           DATE,
    status               VARCHAR(12)   NOT NULL DEFAULT 'REQUESTED'
                         CHECK (status IN ('REQUESTED', 'RECOMMENDED', 'APPROVED', 'APPLIED', 'REJECTED', 'CANCELLED')),
    requested_by_id      BIGINT,
    requested_by_name    VARCHAR(150)  NOT NULL,
    requested_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    recommended_by_name  VARCHAR(150),
    recommended_at       TIMESTAMPTZ,
    recommendation_note  VARCHAR(500),
    decided_by_id        BIGINT,
    decided_by_name      VARCHAR(150),
    decided_at           TIMESTAMPTZ,
    decision_note        VARCHAR(500),
    applied_at           TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version              BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_status_change_until CHECK (until_date IS NULL OR until_date > effective_date)
);
CREATE INDEX ix_status_changes_student ON student_status_changes (tenant_id, student_id, requested_at);
CREATE UNIQUE INDEX uq_status_changes_open ON student_status_changes (student_id) WHERE status IN ('REQUESTED', 'RECOMMENDED', 'APPROVED');

CREATE TABLE student_photos (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    student_id        BIGINT        NOT NULL UNIQUE REFERENCES students (id),
    content_type      VARCHAR(40)   NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    data              BYTEA         NOT NULL,
    size_bytes        INTEGER       NOT NULL,
    uploaded_by_name  VARCHAR(150)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE student_id_cards (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    student_id         BIGINT        NOT NULL REFERENCES students (id),
    card_no            VARCHAR(40)   NOT NULL,
    verification_code  VARCHAR(20)   NOT NULL UNIQUE,
    valid_until        DATE          NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'VALID' CHECK (status IN ('VALID', 'REPLACED', 'REVOKED')),
    issued_by_name     VARCHAR(150)  NOT NULL,
    issued_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    ended_reason       VARCHAR(300),
    ended_at           TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_id_cards_no UNIQUE (tenant_id, card_no)
);
CREATE UNIQUE INDEX uq_id_cards_valid ON student_id_cards (student_id) WHERE status = 'VALID';

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['student_status_changes', 'student_photos', 'student_id_cards'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
