-- =====================================================================
-- CIMS V8: admission letters and joining instructions (FR-ADM-13) with
-- public QR verification (BP-16).
--
-- A letter is issued when the applicant accepts the offer. What is printed
-- is stored as a snapshot (content), so a reprint is identical and the
-- public verification page shows exactly what the institution issued.
-- Corrections create a new edition with a new verification code; the old
-- one then verifies as superseded.
-- =====================================================================

-- Joining instructions per admission window (reporting date, venue, what to bring, signatory …)
ALTER TABLE admission_windows ADD COLUMN joining_instructions JSONB;

CREATE TABLE admission_letters (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT       NOT NULL REFERENCES tenants (id),
    application_id      BIGINT       NOT NULL REFERENCES applications (id),
    letter_no           VARCHAR(40)  NOT NULL,
    edition             SMALLINT     NOT NULL CHECK (edition >= 1),
    verification_code   VARCHAR(19)  NOT NULL,
    status              VARCHAR(12)  NOT NULL CHECK (status IN ('VALID', 'SUPERSEDED', 'REVOKED')),
    content             JSONB        NOT NULL,
    content_sha256      CHAR(64)     NOT NULL,
    issued_at           TIMESTAMPTZ  NOT NULL,
    issued_by           BIGINT,                      -- null when issued automatically on acceptance
    issued_by_name      VARCHAR(200) NOT NULL,
    reissue_reason      VARCHAR(300),
    revoked_at          TIMESTAMPTZ,
    revoked_by_name     VARCHAR(200),
    revoke_reason       VARCHAR(300),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version             BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_admission_letters_edition UNIQUE (application_id, edition),
    CONSTRAINT uq_admission_letters_no UNIQUE (tenant_id, letter_no, edition),
    CONSTRAINT ck_admission_letters_revoked CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);
-- Codes are random and globally unique, so a code identifies one letter anywhere
CREATE UNIQUE INDEX uq_admission_letters_code ON admission_letters (verification_code);
-- One valid letter per application
CREATE UNIQUE INDEX uq_admission_letters_valid ON admission_letters (application_id) WHERE status = 'VALID';

ALTER TABLE admission_letters ENABLE ROW LEVEL SECURITY;
ALTER TABLE admission_letters FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON admission_letters
    USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());
