-- ===========================================================================================================================
-- V70: services Msingi sells to institutions besides the subscription and SMS bundles (training, data migration, an extra
-- sender name, a pack of SMS …). Msingi defines them in the console; an institution requests one, an invoice is issued at
-- once with its payment reference and the payment method Msingi chose, and when it is paid the service is delivered:
-- automatically for SMS packs, otherwise Msingi's staff are told to deliver it and mark it delivered.
-- ===========================================================================================================================
CREATE TABLE billing_services (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(30)   NOT NULL UNIQUE,
    name            VARCHAR(120)  NOT NULL,
    description     VARCHAR(1000),
    unit_label      VARCHAR(40)   NOT NULL DEFAULT 'unit',      -- what one is: a day, a sender name, a pack …
    unit_price      NUMERIC(14,2) NOT NULL CHECK (unit_price > 0),
    min_quantity    INT           NOT NULL DEFAULT 1 CHECK (min_quantity > 0),
    max_quantity    INT           NOT NULL DEFAULT 1 CHECK (max_quantity >= min_quantity),
    vat_percent     NUMERIC(5,2)  NOT NULL DEFAULT 18 CHECK (vat_percent BETWEEN 0 AND 100),
    pay_methods     VARCHAR(6)    NOT NULL DEFAULT 'BOTH' CHECK (pay_methods IN ('BOTH', 'MOBILE', 'BANK')),
    delivery        VARCHAR(16)   NOT NULL CHECK (delivery IN ('SMS_CREDIT', 'MSINGI_DELIVERS')),
    sms_per_unit    INT           CHECK (sms_per_unit IS NULL OR sms_per_unit > 0),   -- SMS_CREDIT: SMS added per unit bought
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by_name VARCHAR(150),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (delivery <> 'SMS_CREDIT' OR sms_per_unit IS NOT NULL)
);

CREATE TABLE billing_service_orders (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants(id),
    service_id        BIGINT        NOT NULL REFERENCES billing_services(id),
    quantity          INT           NOT NULL CHECK (quantity > 0),
    invoice_id        BIGINT        NOT NULL REFERENCES billing_invoices(id),
    status            VARCHAR(10)   NOT NULL DEFAULT 'REQUESTED' CHECK (status IN ('REQUESTED', 'PAID', 'DELIVERED', 'CANCELLED')),
    note              VARCHAR(500),                              -- what the institution wrote with its request
    requested_by_name VARCHAR(150),
    requested_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    paid_at           TIMESTAMPTZ,
    delivered_at      TIMESTAMPTZ,
    delivered_by_name VARCHAR(150),
    delivery_note     VARCHAR(500)
);
CREATE INDEX billing_service_orders_invoice ON billing_service_orders (invoice_id);
ALTER TABLE billing_service_orders ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_service_orders FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_or_tenant ON billing_service_orders USING (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant())
    WITH CHECK (cims_current_tenant() IS NULL OR tenant_id = cims_current_tenant());

ALTER TABLE billing_invoices DROP CONSTRAINT billing_invoices_kind_check;
ALTER TABLE billing_invoices ADD CONSTRAINT billing_invoices_kind_check CHECK (kind IN ('LICENCE', 'USAGE', 'OTHER', 'SMS', 'SERVICE'));
ALTER TABLE billing_invoices ADD COLUMN pay_methods VARCHAR(6) CHECK (pay_methods IS NULL OR pay_methods IN ('BOTH', 'MOBILE', 'BANK'));
ALTER TABLE billing_invoice_lines DROP CONSTRAINT billing_invoice_lines_kind_check;
ALTER TABLE billing_invoice_lines ADD CONSTRAINT billing_invoice_lines_kind_check
    CHECK (kind IN ('SETUP', 'LICENCE', 'STUDENTS', 'APPLICATIONS', 'APPLICATION_SHARE', 'OTHER', 'DISCOUNT', 'SMS_BUNDLE', 'SERVICE'));
