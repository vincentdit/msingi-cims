-- Hostel Warden: wardens assigned to hostels (Document 03: a warden's data scope is the assigned hostels), and repair requests
-- reported by students or wardens and tracked to completion (FR-HST-06).

CREATE TABLE hostel_wardens (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT NOT NULL REFERENCES tenants (id),
    hostel_id  BIGINT NOT NULL REFERENCES hostels (id) ON DELETE CASCADE,
    user_id    BIGINT NOT NULL REFERENCES user_accounts (id),
    CONSTRAINT uq_hostel_wardens UNIQUE (hostel_id, user_id)
);

CREATE TABLE hostel_repairs (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    repair_no          VARCHAR(40)   NOT NULL,
    hostel_id          BIGINT        NOT NULL REFERENCES hostels (id),
    room_id            BIGINT        REFERENCES hostel_rooms (id),
    student_id         BIGINT        REFERENCES students (id),
    category           VARCHAR(12)   NOT NULL CHECK (category IN ('ELECTRICAL', 'PLUMBING', 'FURNITURE', 'STRUCTURE', 'CLEANING', 'SECURITY', 'OTHER')),
    priority           VARCHAR(8)    NOT NULL DEFAULT 'NORMAL' CHECK (priority IN ('NORMAL', 'URGENT')),
    description        VARCHAR(1000) NOT NULL,
    status             VARCHAR(12)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    reported_by_name   VARCHAR(150)  NOT NULL,
    reported_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    note               VARCHAR(1000),
    updated_by_name    VARCHAR(150),
    resolved_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_hostel_repairs_no UNIQUE (tenant_id, repair_no)
);
CREATE INDEX ix_hostel_repairs_status ON hostel_repairs (tenant_id, status);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['hostel_wardens', 'hostel_repairs']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
