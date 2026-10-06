-- =====================================================================
-- CIMS V7: NACTVET verification and offer acceptance (BP-04)
--
-- Application lifecycle after selection:
--   SELECTED ─submit─▶ PENDING_VERIFICATION ─▶ OFFERED ─▶ ACCEPTED
--                         │      ▲               ├─▶ DECLINED
--                         │      │ resolve query └─▶ OFFER_LAPSED
--                         ├─▶ QUERIED (back to SELECTED once resolved)
--                         └─▶ NACTVET_REJECTED
-- DECLINED, OFFER_LAPSED and NACTVET_REJECTED release the place to the
-- next selection round.
-- =====================================================================

ALTER TABLE applications DROP CONSTRAINT IF EXISTS applications_status_check;
ALTER TABLE applications ALTER COLUMN status TYPE VARCHAR(24);
ALTER TABLE applications ADD CONSTRAINT applications_status_check CHECK (status IN (
    'DRAFT', 'PAYMENT_PENDING', 'SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED', 'WITHDRAWN',
    'PENDING_VERIFICATION', 'QUERIED', 'NACTVET_REJECTED', 'OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED'));

ALTER TABLE applications
    ADD COLUMN verification_remarks     VARCHAR(300),   -- institution's note sent with a resubmission
    ADD COLUMN verification_reason      VARCHAR(300),   -- NACTVET query or rejection reason (latest)
    ADD COLUMN nactvet_verification_no  VARCHAR(40),
    ADD COLUMN verified_at              TIMESTAMPTZ,
    ADD COLUMN multiple_admission       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN offer_issued_at          TIMESTAMPTZ,
    ADD COLUMN offer_expires_at         TIMESTAMPTZ,
    ADD COLUMN offer_responded_at       TIMESTAMPTZ,
    ADD COLUMN offer_decline_reason     VARCHAR(300),
    ADD COLUMN confirmation_attempts    SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE applications ADD CONSTRAINT ck_applications_offer CHECK (
    status NOT IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'OFFER_LAPSED')
    OR (nactvet_verification_no IS NOT NULL AND offer_issued_at IS NOT NULL AND offer_expires_at IS NOT NULL));
CREATE INDEX ix_applications_offer_expiry ON applications (offer_expires_at) WHERE status = 'OFFERED';

-- How long a verified applicant has to accept the offer
ALTER TABLE admission_windows
    ADD COLUMN offer_acceptance_days SMALLINT NOT NULL DEFAULT 14 CHECK (offer_acceptance_days BETWEEN 1 AND 90);

-- A batch of selected applicants sent to NACTVET for verification
CREATE TABLE nactvet_submissions (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL REFERENCES tenants (id),
    window_id             BIGINT       NOT NULL REFERENCES admission_windows (id),
    batch_no              SMALLINT     NOT NULL,
    channel               VARCHAR(8)   NOT NULL CHECK (channel IN ('API', 'FILE')),
    status                VARCHAR(12)  NOT NULL CHECK (status IN ('SUBMITTED', 'COMPLETED')),
    external_ref          VARCHAR(60),                 -- NACTVET's reference for an API submission
    submitted_at          TIMESTAMPTZ  NOT NULL,
    submitted_by          BIGINT       NOT NULL,
    submitted_by_name     VARCHAR(200) NOT NULL,
    last_outcome_at       TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_nactvet_submissions UNIQUE (window_id, batch_no),
    CONSTRAINT ck_nactvet_submissions_ref CHECK (channel = 'FILE' OR external_ref IS NOT NULL)
);

-- One applicant in a submission, with NACTVET's outcome
CREATE TABLE nactvet_submission_items (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT       NOT NULL REFERENCES tenants (id),
    submission_id         BIGINT       NOT NULL REFERENCES nactvet_submissions (id),
    application_id        BIGINT       NOT NULL REFERENCES applications (id),
    window_programme_id   BIGINT       NOT NULL REFERENCES window_programmes (id),
    index_number          VARCHAR(30)  NOT NULL,
    programme_code        VARCHAR(30)  NOT NULL,       -- NACTVET programme code at submission time
    remarks               VARCHAR(300),
    outcome               VARCHAR(10)  NOT NULL CHECK (outcome IN ('PENDING', 'VERIFIED', 'QUERIED', 'REJECTED')),
    reason                VARCHAR(300),
    verification_no       VARCHAR(40),
    multiple_admission    BOOLEAN      NOT NULL DEFAULT FALSE,
    outcome_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_nactvet_submission_items UNIQUE (submission_id, application_id),
    CONSTRAINT ck_nactvet_items_verified CHECK (outcome <> 'VERIFIED' OR verification_no IS NOT NULL),
    CONSTRAINT ck_nactvet_items_reason CHECK (outcome NOT IN ('QUERIED', 'REJECTED') OR reason IS NOT NULL)
);
-- An applicant can be awaiting NACTVET in only one submission at a time
CREATE UNIQUE INDEX uq_nactvet_items_pending ON nactvet_submission_items (application_id) WHERE outcome = 'PENDING';
CREATE INDEX ix_nactvet_items_submission ON nactvet_submission_items (submission_id, outcome);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['nactvet_submissions', 'nactvet_submission_items']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
