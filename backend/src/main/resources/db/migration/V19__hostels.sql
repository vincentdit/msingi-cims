-- =====================================================================
-- CIMS V19: hostels and accommodation (FR-HST-01..05, BR-28, BRU-18,
-- BP-12, UC-16): bed inventory with gender designation and fee
-- categories, application windows, applications with preferences,
-- allocations (one active allocation per bed, enforced here), hostel
-- invoices with release of unpaid allocations, check-in and check-out
-- with condition, damages and charges.
-- =====================================================================

-- What a bed costs for an academic year (e.g. standard, en-suite)
CREATE TABLE hostel_fee_categories (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)    NOT NULL,
    name        VARCHAR(120)   NOT NULL,
    amount      NUMERIC(12,2)  NOT NULL CHECK (amount >= 0),     -- per bed per academic year
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version     BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_hostel_fee_categories UNIQUE (tenant_id, code)
);

CREATE TABLE hostels (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)   NOT NULL,
    name        VARCHAR(120)  NOT NULL,
    gender      VARCHAR(6)    NOT NULL CHECK (gender IN ('FEMALE', 'MALE', 'MIXED')),   -- MIXED: each room is female or male
    location    VARCHAR(200),
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_hostels UNIQUE (tenant_id, code)
);

CREATE TABLE hostel_rooms (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT        NOT NULL REFERENCES tenants (id),
    hostel_id        BIGINT        NOT NULL REFERENCES hostels (id),
    block            VARCHAR(40)   NOT NULL DEFAULT '',
    floor            SMALLINT      NOT NULL DEFAULT 0,
    room_no          VARCHAR(20)   NOT NULL,
    room_type        VARCHAR(10)   NOT NULL CHECK (room_type IN ('SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD', 'DORMITORY')),
    gender           VARCHAR(6)    NOT NULL CHECK (gender IN ('FEMALE', 'MALE')),
    fee_category_id  BIGINT        NOT NULL REFERENCES hostel_fee_categories (id),
    accessible       BOOLEAN       NOT NULL DEFAULT FALSE,     -- suitable for students with special needs
    status           VARCHAR(12)   NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MAINTENANCE', 'CLOSED')),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_hostel_rooms UNIQUE (hostel_id, block, room_no)
);
CREATE INDEX ix_hostel_rooms_hostel ON hostel_rooms (tenant_id, hostel_id);

CREATE TABLE hostel_beds (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT       NOT NULL REFERENCES tenants (id),
    room_id    BIGINT       NOT NULL REFERENCES hostel_rooms (id) ON DELETE CASCADE,
    label      VARCHAR(10)  NOT NULL,
    status     VARCHAR(12)  NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'MAINTENANCE')),
    CONSTRAINT uq_hostel_beds UNIQUE (room_id, label)
);

-- An application window for an academic year
CREATE TABLE hostel_windows (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id      BIGINT        NOT NULL REFERENCES academic_years (id),
    name                  VARCHAR(120)  NOT NULL,
    opens_on              DATE          NOT NULL,
    closes_on             DATE          NOT NULL,
    occupancy_starts      DATE,
    occupancy_ends        DATE,
    payment_days          SMALLINT      NOT NULL DEFAULT 14 CHECK (payment_days BETWEEN 1 AND 90),  -- unpaid allocations are released after this
    first_years_first     BOOLEAN       NOT NULL DEFAULT TRUE,
    special_needs_first   BOOLEAN       NOT NULL DEFAULT TRUE,
    paid_fees_first       BOOLEAN       NOT NULL DEFAULT TRUE,   -- students meeting the hostel fees gate before others
    created_by_name       VARCHAR(150)  NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_hostel_windows_dates CHECK (closes_on >= opens_on)
);

CREATE TABLE hostel_applications (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT         NOT NULL REFERENCES tenants (id),
    window_id              BIGINT         NOT NULL REFERENCES hostel_windows (id),
    student_id             BIGINT         NOT NULL REFERENCES students (id),
    preference1_id         BIGINT         REFERENCES hostels (id),
    preference2_id         BIGINT         REFERENCES hostels (id),
    preference3_id         BIGINT         REFERENCES hostels (id),
    room_type              VARCHAR(10)    CHECK (room_type IN ('SINGLE', 'DOUBLE', 'TRIPLE', 'QUAD', 'DORMITORY')),
    special_needs          BOOLEAN        NOT NULL DEFAULT FALSE,
    special_needs_note     VARCHAR(500),
    status                 VARCHAR(10)    NOT NULL DEFAULT 'SUBMITTED'
                           CHECK (status IN ('SUBMITTED', 'ALLOCATED', 'WAITLISTED', 'RELEASED', 'CANCELLED')),
    submitted_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    cancelled_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_hostel_applications UNIQUE (window_id, student_id)
);
CREATE INDEX ix_hostel_applications_window ON hostel_applications (tenant_id, window_id, status);

CREATE TABLE hostel_allocations (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             BIGINT         NOT NULL REFERENCES tenants (id),
    application_id        BIGINT         NOT NULL REFERENCES hostel_applications (id),
    window_id             BIGINT         NOT NULL REFERENCES hostel_windows (id),
    student_id            BIGINT         NOT NULL REFERENCES students (id),
    bed_id                BIGINT         NOT NULL REFERENCES hostel_beds (id),
    status                VARCHAR(12)    NOT NULL DEFAULT 'ALLOCATED'
                          CHECK (status IN ('ALLOCATED', 'CHECKED_IN', 'CHECKED_OUT', 'RELEASED', 'CANCELLED')),
    fee                   NUMERIC(12,2)  NOT NULL,
    invoice_id            BIGINT         REFERENCES invoices (id),
    pay_by                DATE           NOT NULL,
    method                VARCHAR(6)     NOT NULL CHECK (method IN ('AUTO', 'MANUAL')),
    allocated_by_name     VARCHAR(150)   NOT NULL,
    allocated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    checked_in_at         TIMESTAMPTZ,
    checked_in_by_name    VARCHAR(150),
    check_in_condition    VARCHAR(500),
    checked_out_at        TIMESTAMPTZ,
    checked_out_by_name   VARCHAR(150),
    check_out_condition   VARCHAR(500),
    damages               VARCHAR(500),
    damage_charge         NUMERIC(12,2)  CHECK (damage_charge >= 0),
    damage_invoice_id     BIGINT         REFERENCES invoices (id),
    released_at           TIMESTAMPTZ,
    release_reason        VARCHAR(300),
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    version               BIGINT         NOT NULL DEFAULT 0
);
-- BRU-18: a bed holds only one active allocation at a time; a student has one active bed per window
CREATE UNIQUE INDEX uq_hostel_allocations_bed ON hostel_allocations (bed_id) WHERE status IN ('ALLOCATED', 'CHECKED_IN');
CREATE UNIQUE INDEX uq_hostel_allocations_student ON hostel_allocations (window_id, student_id) WHERE status IN ('ALLOCATED', 'CHECKED_IN');
CREATE INDEX ix_hostel_allocations_window ON hostel_allocations (tenant_id, window_id, status);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['hostel_fee_categories', 'hostels', 'hostel_rooms', 'hostel_beds', 'hostel_windows', 'hostel_applications',
                             'hostel_allocations']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
