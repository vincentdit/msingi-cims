-- ===========================================================================================================================
-- The Principal signs the institution's letters and documents.
--  * The institution records its Principal: name, title, a scanned signature and the institution's stamp. Each change keeps
--    the earlier record (rows are never changed or deleted), so a document reprinted later still carries the signature it was
--    issued with, even after a new Principal takes office.
--  * Signed documents remember which record signed them: admission letters, statements of results, award certificates and
--    IPT placement letters.
--  * The signature and stamp are never served publicly: only the administrator sees them, and they are printed on documents.
-- ===========================================================================================================================

CREATE TABLE institution_signatories (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    name             VARCHAR(120)  NOT NULL,
    title            VARCHAR(120)  NOT NULL DEFAULT 'Principal',
    signature        BYTEA,
    signature_type   VARCHAR(20)   CHECK (signature_type IN ('image/png', 'image/jpeg')),
    signature_width  INT,
    signature_height INT,
    stamp            BYTEA,
    stamp_type       VARCHAR(20)   CHECK (stamp_type IN ('image/png', 'image/jpeg')),
    stamp_width      INT,
    stamp_height     INT,
    change_note      VARCHAR(200),
    created_by_name  VARCHAR(150)  NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((signature IS NULL) = (signature_type IS NULL)),
    CHECK ((stamp IS NULL) = (stamp_type IS NULL))
);
CREATE INDEX ix_institution_signatories_current ON institution_signatories (tenant_id, id DESC);

ALTER TABLE institution_signatories ENABLE ROW LEVEL SECURITY;
ALTER TABLE institution_signatories FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON institution_signatories
    USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());

ALTER TABLE admission_letters  ADD COLUMN signatory_id BIGINT REFERENCES institution_signatories (id);
ALTER TABLE academic_documents ADD COLUMN signatory_id BIGINT REFERENCES institution_signatories (id);
ALTER TABLE graduands          ADD COLUMN signatory_id BIGINT REFERENCES institution_signatories (id);
ALTER TABLE ipt_placements     ADD COLUMN letter_signatory_id BIGINT REFERENCES institution_signatories (id);
