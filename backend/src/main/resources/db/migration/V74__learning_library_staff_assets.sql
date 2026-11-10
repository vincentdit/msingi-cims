-- ===========================================================================================================================
-- V74: learning materials and assignments for each module offering; the library (catalogue, copies, loans, fines); staff
-- records with qualifications and leave; the asset register with movements and yearly verification, and the stores.
-- ===========================================================================================================================

-- ------------------------------------------------------------------ learning materials (FR-TCH-06)

-- Notes, links and assignments a lecturer posts for the students registered in a module offering. A file is kept in the row.
CREATE TABLE course_materials (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    offering_id      BIGINT         NOT NULL REFERENCES module_offerings (id) ON DELETE CASCADE,
    kind             VARCHAR(10)    NOT NULL CHECK (kind IN ('NOTE', 'ASSIGNMENT')),
    title            VARCHAR(200)   NOT NULL,
    body             VARCHAR(4000),
    link_url         VARCHAR(500),
    file_name        VARCHAR(200),
    content_type     VARCHAR(120),
    size_bytes       INTEGER,
    data             BYTEA,
    due_at           TIMESTAMPTZ,                       -- assignments
    max_score        NUMERIC(6,2)   CHECK (max_score > 0),
    accept_late      BOOLEAN        NOT NULL DEFAULT TRUE,
    published        BOOLEAN        NOT NULL DEFAULT TRUE,
    created_by       BIGINT         REFERENCES user_accounts (id),
    created_by_name  VARCHAR(150)   NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_course_materials_assignment CHECK (kind = 'NOTE' OR due_at IS NOT NULL),
    CONSTRAINT ck_course_materials_file CHECK ((data IS NULL) = (file_name IS NULL))
);
CREATE INDEX ix_course_materials_offering ON course_materials (tenant_id, offering_id, created_at DESC);

-- A student's work for an assignment; handing in again before marking replaces the file
CREATE TABLE assignment_submissions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    material_id      BIGINT         NOT NULL REFERENCES course_materials (id) ON DELETE CASCADE,
    student_id       BIGINT         NOT NULL REFERENCES students (id),
    file_name        VARCHAR(200)   NOT NULL,
    content_type     VARCHAR(120)   NOT NULL,
    size_bytes       INTEGER        NOT NULL,
    data             BYTEA          NOT NULL,
    sha256           CHAR(64)       NOT NULL,
    note             VARCHAR(1000),
    submitted_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    late             BOOLEAN        NOT NULL DEFAULT FALSE,
    times_submitted  SMALLINT       NOT NULL DEFAULT 1,
    score            NUMERIC(6,2)   CHECK (score >= 0),
    feedback         VARCHAR(2000),
    marked_by_name   VARCHAR(150),
    marked_at        TIMESTAMPTZ,
    CONSTRAINT uq_assignment_submissions UNIQUE (material_id, student_id)
);

-- ------------------------------------------------------------------ library (FR-LIB-01..05)

CREATE TABLE library_settings (
    tenant_id            BIGINT         PRIMARY KEY REFERENCES tenants (id),
    student_loan_days    SMALLINT       NOT NULL DEFAULT 14 CHECK (student_loan_days BETWEEN 1 AND 180),
    staff_loan_days      SMALLINT       NOT NULL DEFAULT 30 CHECK (staff_loan_days BETWEEN 1 AND 365),
    student_max_loans    SMALLINT       NOT NULL DEFAULT 3 CHECK (student_max_loans BETWEEN 1 AND 50),
    staff_max_loans      SMALLINT       NOT NULL DEFAULT 10 CHECK (staff_max_loans BETWEEN 1 AND 100),
    max_renewals         SMALLINT       NOT NULL DEFAULT 1 CHECK (max_renewals BETWEEN 0 AND 10),
    fine_per_day         NUMERIC(12,2)  NOT NULL DEFAULT 200 CHECK (fine_per_day >= 0),
    max_fine             NUMERIC(12,2)  NOT NULL DEFAULT 20000 CHECK (max_fine >= 0),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- A title in the catalogue
CREATE TABLE library_items (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT         NOT NULL REFERENCES tenants (id),
    title         VARCHAR(300)   NOT NULL,
    authors       VARCHAR(300),
    isbn          VARCHAR(20),
    publisher     VARCHAR(150),
    year          SMALLINT,
    edition       VARCHAR(40),
    subject       VARCHAR(150),
    call_number   VARCHAR(40),
    kind          VARCHAR(10)    NOT NULL DEFAULT 'BOOK' CHECK (kind IN ('BOOK', 'REFERENCE', 'JOURNAL', 'REPORT', 'MEDIA', 'OTHER')),
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_library_items_title ON library_items (tenant_id, lower(title));
CREATE INDEX ix_library_items_isbn ON library_items (tenant_id, isbn) WHERE isbn IS NOT NULL;

-- A physical copy, labelled with its accession number (the barcode)
CREATE TABLE library_copies (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT         NOT NULL REFERENCES tenants (id),
    item_id        BIGINT         NOT NULL REFERENCES library_items (id) ON DELETE CASCADE,
    barcode        VARCHAR(40)    NOT NULL,
    campus_id      BIGINT         REFERENCES campuses (id),
    status         VARCHAR(10)    NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'ON_LOAN', 'IN_LIBRARY', 'LOST', 'WITHDRAWN')),
    price          NUMERIC(12,2)  CHECK (price >= 0),
    acquired_on    DATE,
    note           VARCHAR(300),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_library_copies UNIQUE (tenant_id, barcode)
);
CREATE INDEX ix_library_copies_item ON library_copies (tenant_id, item_id);

CREATE TABLE library_loans (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenants (id),
    copy_id            BIGINT         NOT NULL REFERENCES library_copies (id),
    student_id         BIGINT         REFERENCES students (id),
    staff_id           BIGINT,                                 -- staff_members (created below)
    borrower_name      VARCHAR(200)   NOT NULL,
    issued_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    due_on             DATE           NOT NULL,
    renewals           SMALLINT       NOT NULL DEFAULT 0,
    returned_at        TIMESTAMPTZ,
    lost               BOOLEAN        NOT NULL DEFAULT FALSE,
    fine               NUMERIC(12,2)  NOT NULL DEFAULT 0,
    fine_status        VARCHAR(8)     NOT NULL DEFAULT 'NONE' CHECK (fine_status IN ('NONE', 'BILLED', 'TO_PAY', 'PAID', 'WAIVED')),
    fine_note          VARCHAR(300),
    invoice_id         BIGINT         REFERENCES invoices (id),
    issued_by_name     VARCHAR(150)   NOT NULL,
    returned_by_name   VARCHAR(150),
    reminded_due_at    TIMESTAMPTZ,
    reminded_late_at   TIMESTAMPTZ,
    CONSTRAINT ck_library_loans_borrower CHECK ((student_id IS NULL) <> (staff_id IS NULL))
);
-- one open loan per copy
CREATE UNIQUE INDEX uq_library_loans_open ON library_loans (copy_id) WHERE returned_at IS NULL;
CREATE INDEX ix_library_loans_student ON library_loans (tenant_id, student_id) WHERE student_id IS NOT NULL;
CREATE INDEX ix_library_loans_staff ON library_loans (tenant_id, staff_id) WHERE staff_id IS NOT NULL;
CREATE INDEX ix_library_loans_open_due ON library_loans (tenant_id, due_on) WHERE returned_at IS NULL;

-- ------------------------------------------------------------------ staff records and leave (FR-HR-01..04)

-- Everyone who works for the institution, with or without a CIMS account
CREATE TABLE staff_members (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id              BIGINT         NOT NULL REFERENCES tenants (id),
    user_id                BIGINT         REFERENCES user_accounts (id) ON DELETE SET NULL,
    staff_no               VARCHAR(30)    NOT NULL,
    title                  VARCHAR(10),
    full_name              VARCHAR(200)   NOT NULL,
    gender                 CHAR(1)        NOT NULL CHECK (gender IN ('M', 'F')),
    date_of_birth          DATE,
    nationality            VARCHAR(60)    NOT NULL DEFAULT 'Tanzanian',
    nin                    VARCHAR(25),
    phone                  VARCHAR(20),
    email                  VARCHAR(120),
    job_title              VARCHAR(120),
    category               VARCHAR(14)    NOT NULL CHECK (category IN ('ACADEMIC', 'ADMINISTRATIVE', 'TECHNICAL', 'SUPPORT')),
    employment             VARCHAR(10)    NOT NULL CHECK (employment IN ('PERMANENT', 'CONTRACT', 'PART_TIME', 'VOLUNTEER')),
    department_id          BIGINT         REFERENCES departments (id),
    campus_id              BIGINT         REFERENCES campuses (id),
    first_appointed_on     DATE,
    contract_ends_on       DATE,
    highest_qualification  VARCHAR(12)    CHECK (highest_qualification IN ('NONE', 'CERTIFICATE', 'DIPLOMA', 'ADVANCED_DIPLOMA', 'BACHELOR',
                                                                           'POSTGRADUATE_DIPLOMA', 'MASTERS', 'PHD')),
    specialisation         VARCHAR(150),
    tutor_reg_no           VARCHAR(40),                        -- registration as a tutor, where the regulator issues one
    status                 VARCHAR(8)     NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'LEFT')),
    left_on                DATE,
    left_reason            VARCHAR(30)    CHECK (left_reason IN ('RESIGNED', 'RETIRED', 'CONTRACT_ENDED', 'DISMISSED', 'TRANSFERRED', 'DECEASED', 'OTHER')),
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_staff_members_no UNIQUE (tenant_id, staff_no)
);
CREATE UNIQUE INDEX uq_staff_members_user ON staff_members (tenant_id, user_id) WHERE user_id IS NOT NULL;
ALTER TABLE library_loans ADD CONSTRAINT fk_library_loans_staff FOREIGN KEY (staff_id) REFERENCES staff_members (id);

CREATE TABLE staff_qualifications (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT         NOT NULL REFERENCES tenants (id),
    staff_id      BIGINT         NOT NULL REFERENCES staff_members (id) ON DELETE CASCADE,
    level         VARCHAR(20)    NOT NULL,
    award         VARCHAR(200)   NOT NULL,
    institution   VARCHAR(200)   NOT NULL,
    year          SMALLINT       NOT NULL,
    file_name     VARCHAR(200),
    content_type  VARCHAR(60),
    data          BYTEA,
    verified_by_name VARCHAR(150),
    verified_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE leave_types (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT         NOT NULL REFERENCES tenants (id),
    code            VARCHAR(20)    NOT NULL,
    name            VARCHAR(80)    NOT NULL,
    days_per_year   SMALLINT       CHECK (days_per_year >= 0),     -- empty: no yearly limit (granted case by case)
    only_gender     CHAR(1)        CHECK (only_gender IN ('M', 'F')),
    calendar_days   BOOLEAN        NOT NULL DEFAULT FALSE,         -- counted in calendar days (maternity), not working days
    active          BOOLEAN        NOT NULL DEFAULT TRUE,
    sort_order      SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_leave_types UNIQUE (tenant_id, code)
);

-- Public holidays that fall on weekdays and are not in the fixed national list (Easter, Eid and the like)
CREATE TABLE public_holidays (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenants (id),
    day         DATE           NOT NULL,
    name        VARCHAR(80)    NOT NULL,
    CONSTRAINT uq_public_holidays UNIQUE (tenant_id, day)
);

CREATE TABLE leave_requests (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    staff_id          BIGINT         NOT NULL REFERENCES staff_members (id) ON DELETE CASCADE,
    leave_type_id     BIGINT         NOT NULL REFERENCES leave_types (id),
    start_on          DATE           NOT NULL,
    end_on            DATE           NOT NULL,
    days              SMALLINT       NOT NULL CHECK (days > 0),
    reason            VARCHAR(500),
    relief_name       VARCHAR(150),                           -- who covers the work
    status            VARCHAR(10)    NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    submitted_by_name VARCHAR(150)   NOT NULL,
    submitted_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    decided_by_name   VARCHAR(150),
    decided_at        TIMESTAMPTZ,
    decision_note     VARCHAR(500),
    CONSTRAINT ck_leave_requests_dates CHECK (end_on >= start_on)
);
CREATE INDEX ix_leave_requests_staff ON leave_requests (tenant_id, staff_id, start_on DESC);

-- Days added to or taken from an entitlement (days carried forward, an earlier balance when CIMS started)
CREATE TABLE leave_adjustments (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    staff_id         BIGINT         NOT NULL REFERENCES staff_members (id) ON DELETE CASCADE,
    leave_type_id    BIGINT         NOT NULL REFERENCES leave_types (id),
    year             SMALLINT       NOT NULL,
    days             SMALLINT       NOT NULL CHECK (days <> 0),
    reason           VARCHAR(300)   NOT NULL,
    created_by_name  VARCHAR(150)   NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- ------------------------------------------------------------------ assets and stores (FR-AST-01..04)

CREATE TABLE assets (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT         NOT NULL REFERENCES tenants (id),
    tag_no              VARCHAR(40)    NOT NULL,
    name                VARCHAR(200)   NOT NULL,
    category            VARCHAR(14)    NOT NULL CHECK (category IN ('FURNITURE', 'ICT', 'LAB_EQUIPMENT', 'WORKSHOP', 'VEHICLE', 'BUILDING', 'LAND',
                                                                    'OFFICE_EQUIPMENT', 'OTHER')),
    description         VARCHAR(500),
    serial_no           VARCHAR(80),
    campus_id           BIGINT         REFERENCES campuses (id),
    venue_id            BIGINT         REFERENCES teaching_venues (id) ON DELETE SET NULL,
    location            VARCHAR(150),
    department_id       BIGINT         REFERENCES departments (id),
    custodian_name      VARCHAR(150),
    acquired_on         DATE,
    cost                NUMERIC(14,2)  CHECK (cost >= 0),
    useful_life_years   SMALLINT       CHECK (useful_life_years > 0),
    funding             VARCHAR(10)    CHECK (funding IN ('GOVERNMENT', 'OWN', 'DONOR', 'OTHER')),
    supplier            VARCHAR(150),
    condition           VARCHAR(14)    NOT NULL DEFAULT 'GOOD' CHECK (condition IN ('NEW', 'GOOD', 'FAIR', 'POOR', 'UNSERVICEABLE')),
    status              VARCHAR(12)    NOT NULL DEFAULT 'IN_USE' CHECK (status IN ('IN_USE', 'IN_STORE', 'UNDER_REPAIR', 'DISPOSED', 'LOST')),
    disposed_on         DATE,
    disposal_note       VARCHAR(300),
    last_verified_at    TIMESTAMPTZ,
    created_by_name     VARCHAR(150)   NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_assets_tag UNIQUE (tenant_id, tag_no)
);
CREATE INDEX ix_assets_place ON assets (tenant_id, campus_id, department_id);

-- Where an asset went, who holds it, and changes of condition and status
CREATE TABLE asset_events (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id     BIGINT         NOT NULL REFERENCES tenants (id),
    asset_id      BIGINT         NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    kind          VARCHAR(10)    NOT NULL CHECK (kind IN ('ADDED', 'MOVED', 'CONDITION', 'STATUS', 'VERIFIED', 'DISPOSED')),
    details       VARCHAR(500)   NOT NULL,
    by_name       VARCHAR(150)   NOT NULL,
    at            TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_asset_events_asset ON asset_events (tenant_id, asset_id, at DESC);

-- A verification exercise (usually yearly): every asset is looked for and its condition noted
CREATE TABLE asset_verifications (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    name             VARCHAR(120)   NOT NULL,
    campus_id        BIGINT         REFERENCES campuses (id),
    started_by_name  VARCHAR(150)   NOT NULL,
    started_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    closed_by_name   VARCHAR(150),
    closed_at        TIMESTAMPTZ
);
CREATE TABLE asset_verification_lines (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    verification_id  BIGINT         NOT NULL REFERENCES asset_verifications (id) ON DELETE CASCADE,
    asset_id         BIGINT         NOT NULL REFERENCES assets (id) ON DELETE CASCADE,
    found            BOOLEAN,                                  -- empty until checked
    condition        VARCHAR(14)    CHECK (condition IN ('NEW', 'GOOD', 'FAIR', 'POOR', 'UNSERVICEABLE')),
    note             VARCHAR(300),
    checked_by_name  VARCHAR(150),
    checked_at       TIMESTAMPTZ,
    CONSTRAINT uq_asset_verification_lines UNIQUE (verification_id, asset_id)
);

-- Consumable stock (stationery, cleaning materials, workshop and laboratory consumables)
CREATE TABLE store_items (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    code             VARCHAR(30)    NOT NULL,
    name             VARCHAR(150)   NOT NULL,
    unit             VARCHAR(20)    NOT NULL,
    reorder_level    NUMERIC(12,2)  NOT NULL DEFAULT 0 CHECK (reorder_level >= 0),
    on_hand          NUMERIC(12,2)  NOT NULL DEFAULT 0 CHECK (on_hand >= 0),
    average_cost     NUMERIC(14,2)  NOT NULL DEFAULT 0 CHECK (average_cost >= 0),
    active           BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_store_items UNIQUE (tenant_id, code)
);
CREATE TABLE store_transactions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    item_id          BIGINT         NOT NULL REFERENCES store_items (id),
    kind             VARCHAR(10)    NOT NULL CHECK (kind IN ('RECEIPT', 'ISSUE', 'ADJUSTMENT')),
    quantity         NUMERIC(12,2)  NOT NULL CHECK (quantity <> 0),   -- received or added (+), issued or removed (-)
    unit_cost        NUMERIC(14,2)  CHECK (unit_cost >= 0),
    balance_after    NUMERIC(12,2)  NOT NULL,
    department_id    BIGINT         REFERENCES departments (id),
    party            VARCHAR(150),                           -- the supplier, or who collected the items
    reference        VARCHAR(60),                            -- delivery note, LPO or requisition number
    note             VARCHAR(300),
    by_name          VARCHAR(150)   NOT NULL,
    at               TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_store_transactions_item ON store_transactions (tenant_id, item_id, at DESC);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['course_materials', 'assignment_submissions', 'library_settings', 'library_items', 'library_copies', 'library_loans',
                             'staff_members', 'staff_qualifications', 'leave_types', 'public_holidays', 'leave_requests', 'leave_adjustments',
                             'assets', 'asset_events', 'asset_verifications', 'asset_verification_lines', 'store_items', 'store_transactions']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
