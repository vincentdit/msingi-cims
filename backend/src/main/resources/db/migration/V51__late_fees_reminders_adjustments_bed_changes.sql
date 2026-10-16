-- Batch 10: late-registration fee (BP-05), application-fee reminders (BP-02), finance follow-up of status changes (BP-14),
-- hostel bed changes and refunds (BP-12), refunds of fees paid on an invoice (BP-17).

-- Late registration (BP-05 exception): registering after the deadline, within the late period, costs this fee
ALTER TABLE semesters ADD COLUMN late_registration_fee NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (late_registration_fee >= 0);
ALTER TABLE semester_registrations ADD COLUMN late_fee_invoice_id BIGINT REFERENCES invoices (id);

-- Application-fee reminders (BP-02): applicants who asked for a control number but have not paid are reminded by SMS
ALTER TABLE admission_windows ADD COLUMN fee_reminders BOOLEAN NOT NULL DEFAULT TRUE;
CREATE TABLE application_fee_reminders (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT       NOT NULL REFERENCES tenants (id),
    application_id  BIGINT       NOT NULL REFERENCES applications (id),
    kind            VARCHAR(12)  NOT NULL CHECK (kind IN ('UNPAID', 'CLOSING')),   -- two days after the control number; three days before closing
    sent_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_fee_reminders UNIQUE (application_id, kind)
);

-- Where a waiver or refund request came from when CIMS raised it itself (the bursary still decides it)
ALTER TABLE finance_adjustments
    ADD COLUMN origin    VARCHAR(20) CHECK (origin IN ('STATUS_CHANGE', 'HOSTEL_CHANGE', 'HOSTEL_CHECKOUT', 'HOSTEL_CANCEL')),
    ADD COLUMN origin_id BIGINT;
-- a refund with an invoice pays back part of what was paid on that invoice (the invoice is reduced by a credit note)
COMMENT ON COLUMN finance_adjustments.invoice_id IS 'Waiver, discount, scholarship: the invoice credited. Refund: the invoice whose payment is refunded, or none to refund credit.';

-- Hostel bed changes (BP-12): moving a student to another bed, with the fee difference for the rest of the occupancy period
CREATE TABLE hostel_bed_changes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    allocation_id     BIGINT        NOT NULL REFERENCES hostel_allocations (id),
    from_bed_id       BIGINT        NOT NULL REFERENCES hostel_beds (id),
    to_bed_id         BIGINT        NOT NULL REFERENCES hostel_beds (id),
    old_fee           NUMERIC(12,2) NOT NULL,
    new_fee           NUMERIC(12,2) NOT NULL,
    difference        NUMERIC(12,2) NOT NULL,      -- positive: charged; negative: credited or refunded (part of the year left)
    invoice_id        BIGINT        REFERENCES invoices (id),
    adjustment_id     BIGINT        REFERENCES finance_adjustments (id),
    reason            VARCHAR(500)  NOT NULL,
    changed_by_name   VARCHAR(150)  NOT NULL,
    changed_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK (from_bed_id <> to_bed_id)
);
CREATE INDEX ix_hostel_bed_changes_allocation ON hostel_bed_changes (allocation_id);
ALTER TABLE hostel_allocations ADD COLUMN refund_adjustment_id BIGINT REFERENCES finance_adjustments (id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['application_fee_reminders', 'hostel_bed_changes']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
