-- Ownership follows NACTVET's categories: Government (PUBLIC), Private and Faith-based (FBO).
-- Only public institutions collect fees through GePG. Private and faith-based institutions collect into their own mobile money
-- and bank accounts: CIMS issues its own payment reference per invoice, and the institution lists where to pay with it.

ALTER TABLE tenants DROP CONSTRAINT tenants_ownership_check;
ALTER TABLE tenants ADD CONSTRAINT tenants_ownership_check CHECK (ownership IN ('PUBLIC', 'PRIVATE', 'FBO'));

-- The institution's collection accounts: mobile money business (pay-bill) numbers, which take a reference, and bank accounts.
CREATE TABLE payment_channels (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    provider        VARCHAR(20)   NOT NULL,
    kind            VARCHAR(12)   NOT NULL CHECK (kind IN ('MOBILE_MONEY', 'BANK')),
    account_name    VARCHAR(150)  NOT NULL,
    account_number  VARCHAR(40)   NOT NULL,
    branch          VARCHAR(100),
    instructions    VARCHAR(500),
    active          BOOLEAN       NOT NULL DEFAULT true,
    sort_order      INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_channels UNIQUE (tenant_id, provider, account_number)
);

ALTER TABLE payment_channels ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_channels FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment_channels USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());

-- Who issued the number: GePG (control number) or CIMS (payment reference for the institution's own accounts).
ALTER TABLE control_numbers ADD COLUMN issuer VARCHAR(12) NOT NULL DEFAULT 'GEPG' CHECK (issuer IN ('GEPG', 'COLLECTION'));

-- Payments collected into the institution's own accounts (reported by the collector or posted from its statement).
ALTER TABLE payments DROP CONSTRAINT payments_source_check;
ALTER TABLE payments ADD CONSTRAINT payments_source_check CHECK (source IN ('GEPG', 'COLLECTION', 'BANK', 'INTERNAL'));

ALTER TABLE integration_calls DROP CONSTRAINT integration_calls_system_check;
ALTER TABLE integration_calls ADD CONSTRAINT integration_calls_system_check
    CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'NACTVET_CATALOGUE', 'GEPG', 'COLLECTION', 'SMS'));
