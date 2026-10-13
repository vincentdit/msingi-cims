-- =====================================================================
-- CIMS V29: the institution's own photo (e.g. the main campus) shown on
-- the admission portal's home page, uploaded by the administrator.
-- Stored in the database so it travels with backups and stays isolated
-- per institution by row-level security.
-- =====================================================================

CREATE TABLE institution_photos (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    kind              VARCHAR(20)   NOT NULL CHECK (kind IN ('CAMPUS')),
    content_type      VARCHAR(40)   NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    data              BYTEA         NOT NULL,
    size_bytes        INTEGER       NOT NULL,
    width             INTEGER,
    height            INTEGER,
    caption           VARCHAR(150),
    uploaded_by_name  VARCHAR(150)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_institution_photos_kind UNIQUE (tenant_id, kind)
);

ALTER TABLE institution_photos ENABLE ROW LEVEL SECURITY;
ALTER TABLE institution_photos FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON institution_photos
    USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());
