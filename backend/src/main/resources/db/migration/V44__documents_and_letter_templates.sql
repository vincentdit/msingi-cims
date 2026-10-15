-- FR-ADM-07: documents applicants upload (PDF/JPG/PNG, size-limited), as the admission window asks, checked by the office.
-- FR-ADM-S05: the wording of letters (admission letter, IPT placement letter) edited by the institution.

CREATE TABLE window_document_types (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenants (id),
    window_id     BIGINT        NOT NULL REFERENCES admission_windows (id) ON DELETE CASCADE,
    code          VARCHAR(30)   NOT NULL,
    name          VARCHAR(150)  NOT NULL,
    description   VARCHAR(500),
    required      BOOLEAN       NOT NULL DEFAULT true,
    max_kb        INT           NOT NULL DEFAULT 2048 CHECK (max_kb BETWEEN 50 AND 5120),
    sort_order    INT           NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_window_document_types UNIQUE (window_id, code)
);

CREATE TABLE application_documents (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    application_id    BIGINT        NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    document_type_id  BIGINT        NOT NULL REFERENCES window_document_types (id) ON DELETE CASCADE,
    file_name         VARCHAR(200)  NOT NULL,
    content_type      VARCHAR(40)   NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    data              BYTEA         NOT NULL,
    size_bytes        INT           NOT NULL,
    sha256            CHAR(64)      NOT NULL,
    status            VARCHAR(10)   NOT NULL DEFAULT 'UPLOADED' CHECK (status IN ('UPLOADED', 'ACCEPTED', 'REJECTED')),
    review_note       VARCHAR(500),
    reviewed_by_name  VARCHAR(200),
    reviewed_at       TIMESTAMPTZ,
    uploaded_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_application_documents UNIQUE (application_id, document_type_id),
    CONSTRAINT ck_application_documents_note CHECK (status <> 'REJECTED' OR review_note IS NOT NULL)
);
CREATE INDEX ix_application_documents_app ON application_documents (tenant_id, application_id);

CREATE TABLE letter_templates (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    code             VARCHAR(40)   NOT NULL,
    body             TEXT          NOT NULL,
    revision         INT           NOT NULL DEFAULT 1,
    updated_by_name  VARCHAR(200)  NOT NULL,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_letter_templates UNIQUE (tenant_id, code)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['window_document_types', 'application_documents', 'letter_templates']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
