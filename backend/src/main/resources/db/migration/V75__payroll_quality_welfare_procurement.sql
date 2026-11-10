-- b72: payroll (FR-PAY), quality assurance by student evaluation (FR-QA), discipline and welfare (FR-WEL), procurement (FR-PRC)

-- ===================================================================================================== payroll
-- The institution's statutory rates. Defaults follow Tanzania mainland: PAYE bands for residents; NSSF 10% + 10% (private) or PSSSF
-- 5% + 15% (public); WCF 0.5%; SDL 3.5% (educational institutions are exempt, so off by default); NHIF 3% + 3% where it applies;
-- HESLB 15% of basic salary for loan beneficiaries. Every rate can be changed when the law changes.
CREATE TABLE payroll_settings (
    tenant_id               BIGINT         PRIMARY KEY REFERENCES tenants (id),
    pension_fund            VARCHAR(6)     NOT NULL DEFAULT 'NSSF' CHECK (pension_fund IN ('NSSF', 'PSSSF')),
    pension_employee_rate   NUMERIC(5,2)   NOT NULL DEFAULT 10 CHECK (pension_employee_rate BETWEEN 0 AND 100),
    pension_employer_rate   NUMERIC(5,2)   NOT NULL DEFAULT 10 CHECK (pension_employer_rate BETWEEN 0 AND 100),
    pension_on_basic        BOOLEAN        NOT NULL DEFAULT FALSE,      -- PSSSF: on basic salary; NSSF: on gross pay
    paye_bands              JSONB          NOT NULL DEFAULT '[{"from":0,"rate":0},{"from":270000,"rate":8},{"from":520000,"rate":20},{"from":760000,"rate":25},{"from":1000000,"rate":30}]',
    wcf_rate                NUMERIC(5,2)   NOT NULL DEFAULT 0.5 CHECK (wcf_rate BETWEEN 0 AND 100),
    sdl_applies             BOOLEAN        NOT NULL DEFAULT FALSE,
    sdl_rate                NUMERIC(5,2)   NOT NULL DEFAULT 3.5 CHECK (sdl_rate BETWEEN 0 AND 100),
    nhif_applies            BOOLEAN        NOT NULL DEFAULT FALSE,
    nhif_employee_rate      NUMERIC(5,2)   NOT NULL DEFAULT 3 CHECK (nhif_employee_rate BETWEEN 0 AND 100),
    nhif_employer_rate      NUMERIC(5,2)   NOT NULL DEFAULT 3 CHECK (nhif_employer_rate BETWEEN 0 AND 100),
    heslb_rate              NUMERIC(5,2)   NOT NULL DEFAULT 15 CHECK (heslb_rate BETWEEN 0 AND 100),
    updated_by_name         VARCHAR(150),
    updated_at              TIMESTAMPTZ
);

-- A member of staff's pay details (one row per staff record on the payroll)
CREATE TABLE staff_pay (
    staff_id        BIGINT         PRIMARY KEY REFERENCES staff_members (id) ON DELETE CASCADE,
    tenant_id       BIGINT         NOT NULL REFERENCES tenants (id),
    on_payroll      BOOLEAN        NOT NULL DEFAULT TRUE,
    basic_salary    NUMERIC(14,2)  NOT NULL DEFAULT 0 CHECK (basic_salary >= 0),
    bank_name       VARCHAR(80),
    bank_branch     VARCHAR(80),
    bank_account    VARCHAR(40),
    tin             VARCHAR(20),
    pension_no      VARCHAR(30),
    nhif_no         VARCHAR(30),
    heslb_loan      BOOLEAN        NOT NULL DEFAULT FALSE,
    heslb_index_no  VARCHAR(30),
    updated_by_name VARCHAR(150),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- Standing allowances and deductions (housing, transport, responsibility; SACCOS, salary advance, union dues). A deduction with a
-- balance (a loan or an advance) stops when it is paid off.
CREATE TABLE staff_pay_items (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenants (id),
    staff_id    BIGINT         NOT NULL REFERENCES staff_members (id) ON DELETE CASCADE,
    kind        VARCHAR(10)    NOT NULL CHECK (kind IN ('ALLOWANCE', 'DEDUCTION')),
    name        VARCHAR(80)    NOT NULL,
    amount      NUMERIC(14,2)  NOT NULL CHECK (amount > 0),
    taxable     BOOLEAN        NOT NULL DEFAULT TRUE,         -- allowances only
    balance     NUMERIC(14,2)  CHECK (balance >= 0),          -- deductions only: what is still owed; empty for a standing deduction
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_by_name VARCHAR(150) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX ix_staff_pay_items_staff ON staff_pay_items (tenant_id, staff_id);

-- One payroll a month: prepared, approved by someone else, then paid
CREATE TABLE payroll_runs (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenants (id),
    period             DATE           NOT NULL,                 -- the first day of the month paid
    status             VARCHAR(10)    NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'APPROVED', 'PAID')),
    staff_count        INTEGER        NOT NULL DEFAULT 0,
    gross_total        NUMERIC(16,2)  NOT NULL DEFAULT 0,
    net_total          NUMERIC(16,2)  NOT NULL DEFAULT 0,
    employer_total     NUMERIC(16,2)  NOT NULL DEFAULT 0,       -- employer pension, NHIF, WCF and SDL
    rates              JSONB          NOT NULL,                 -- the rates used, kept with the payroll
    prepared_by        BIGINT,
    prepared_by_name   VARCHAR(150)   NOT NULL,
    prepared_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    approved_by        BIGINT,
    approved_by_name   VARCHAR(150),
    approved_at        TIMESTAMPTZ,
    paid_by_name       VARCHAR(150),
    paid_at            TIMESTAMPTZ,
    paid_on            DATE,
    CONSTRAINT uq_payroll_runs UNIQUE (tenant_id, period)
);

CREATE TABLE payslips (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT         NOT NULL REFERENCES tenants (id),
    run_id               BIGINT         NOT NULL REFERENCES payroll_runs (id) ON DELETE CASCADE,
    staff_id             BIGINT         NOT NULL REFERENCES staff_members (id),
    staff_no             VARCHAR(30)    NOT NULL,
    full_name            VARCHAR(200)   NOT NULL,
    job_title            VARCHAR(120),
    department           VARCHAR(150),
    bank_name            VARCHAR(80),
    bank_branch          VARCHAR(80),
    bank_account         VARCHAR(40),
    tin                  VARCHAR(20),
    pension_no           VARCHAR(30),
    basic                NUMERIC(14,2)  NOT NULL,
    taxable_allowances   NUMERIC(14,2)  NOT NULL,
    other_allowances     NUMERIC(14,2)  NOT NULL,
    gross                NUMERIC(14,2)  NOT NULL,
    pension_employee     NUMERIC(14,2)  NOT NULL,
    taxable_income       NUMERIC(14,2)  NOT NULL,
    paye                 NUMERIC(14,2)  NOT NULL,
    nhif_employee        NUMERIC(14,2)  NOT NULL,
    heslb                NUMERIC(14,2)  NOT NULL,
    other_deductions     NUMERIC(14,2)  NOT NULL,
    net                  NUMERIC(14,2)  NOT NULL,
    pension_employer     NUMERIC(14,2)  NOT NULL,
    nhif_employer        NUMERIC(14,2)  NOT NULL,
    wcf                  NUMERIC(14,2)  NOT NULL,
    sdl                  NUMERIC(14,2)  NOT NULL,
    lines                JSONB          NOT NULL,     -- each allowance and deduction by name: [{"kind","name","amount","itemId"}]
    CONSTRAINT uq_payslips UNIQUE (run_id, staff_id)
);
CREATE INDEX ix_payslips_staff ON payslips (tenant_id, staff_id);

-- ===================================================================================================== quality assurance
-- The questionnaire students answer about each module and its teaching
CREATE TABLE evaluation_questions (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenants (id),
    section     VARCHAR(10)    NOT NULL CHECK (section IN ('MODULE', 'TEACHING', 'GENERAL')),
    kind        VARCHAR(6)     NOT NULL CHECK (kind IN ('RATING', 'TEXT')),   -- RATING: 1 (strongly disagree) to 5 (strongly agree)
    text        VARCHAR(300)   NOT NULL,
    sort_order  SMALLINT       NOT NULL DEFAULT 0,
    active      BOOLEAN        NOT NULL DEFAULT TRUE
);

-- An evaluation period for one semester's modules
CREATE TABLE evaluation_rounds (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    semester_id      BIGINT         NOT NULL REFERENCES semesters (id),
    name             VARCHAR(120)   NOT NULL,
    opens_on         DATE           NOT NULL,
    closes_on        DATE           NOT NULL,
    min_responses    SMALLINT       NOT NULL DEFAULT 5 CHECK (min_responses >= 1),   -- fewer answers than this: no report, so nobody is identified
    question_ids     JSONB          NOT NULL,                  -- the questions asked, frozen when the round opens
    created_by_name  VARCHAR(150)   NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_evaluation_rounds_dates CHECK (closes_on >= opens_on),
    CONSTRAINT uq_evaluation_rounds UNIQUE (tenant_id, semester_id)
);

-- Who has answered for which module: kept apart from the answers, which carry no student
CREATE TABLE evaluation_done (
    tenant_id    BIGINT   NOT NULL REFERENCES tenants (id),
    round_id     BIGINT   NOT NULL REFERENCES evaluation_rounds (id) ON DELETE CASCADE,
    offering_id  BIGINT   NOT NULL REFERENCES module_offerings (id),
    student_id   BIGINT   NOT NULL REFERENCES students (id),
    PRIMARY KEY (round_id, offering_id, student_id)
);

CREATE TABLE evaluation_responses (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    BIGINT   NOT NULL REFERENCES tenants (id),
    round_id     BIGINT   NOT NULL REFERENCES evaluation_rounds (id) ON DELETE CASCADE,
    offering_id  BIGINT   NOT NULL REFERENCES module_offerings (id),
    ratings      JSONB    NOT NULL,          -- {"questionId": 1..5}
    comments     JSONB    NOT NULL           -- {"questionId": "text"}
);
CREATE INDEX ix_evaluation_responses ON evaluation_responses (tenant_id, round_id, offering_id);

-- ===================================================================================================== discipline and welfare
CREATE TABLE discipline_cases (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenants (id),
    case_no            VARCHAR(40)    NOT NULL,
    student_id         BIGINT         NOT NULL REFERENCES students (id),
    category           VARCHAR(16)    NOT NULL CHECK (category IN ('MISCONDUCT', 'ABSENTEEISM', 'PROPERTY_DAMAGE', 'VIOLENCE', 'SUBSTANCE_ABUSE',
                                                                   'HARASSMENT', 'THEFT', 'HOSTEL_RULES', 'OTHER')),
    occurred_on        DATE           NOT NULL,
    place              VARCHAR(150),
    description        VARCHAR(2000)  NOT NULL,
    reported_by        BIGINT,
    reported_by_name   VARCHAR(150)   NOT NULL,
    reported_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    status             VARCHAR(10)    NOT NULL DEFAULT 'REPORTED' CHECK (status IN ('REPORTED', 'HEARING', 'DECIDED', 'APPEALED', 'CLOSED', 'WITHDRAWN')),
    hearing_at         TIMESTAMPTZ,
    hearing_place      VARCHAR(150),
    committee          VARCHAR(500),
    student_statement  VARCHAR(2000),
    finding            VARCHAR(10)    CHECK (finding IN ('GUILTY', 'NOT_GUILTY')),
    outcome            VARCHAR(18)    CHECK (outcome IN ('NONE', 'WARNING', 'REPRIMAND', 'FINE', 'COMPENSATION', 'COMMUNITY_SERVICE', 'SUSPENSION',
                                                         'EXPULSION')),
    outcome_details    VARCHAR(1000),
    amount             NUMERIC(12,2)  CHECK (amount > 0),
    suspended_from     DATE,
    suspended_to       DATE,
    invoice_id         BIGINT         REFERENCES invoices (id),
    decided_by         BIGINT,
    decided_by_name    VARCHAR(150),
    decided_at         TIMESTAMPTZ,
    appeal_text        VARCHAR(2000),
    appealed_at        TIMESTAMPTZ,
    appeal_result      VARCHAR(10)    CHECK (appeal_result IN ('UPHELD', 'VARIED', 'SET_ASIDE')),
    appeal_note        VARCHAR(1000),
    appeal_decided_by_name VARCHAR(150),
    appeal_decided_at  TIMESTAMPTZ,
    CONSTRAINT uq_discipline_cases UNIQUE (tenant_id, case_no)
);
CREATE INDEX ix_discipline_cases_student ON discipline_cases (tenant_id, student_id);

-- Counselling: only the Dean of Students (welfare) reads these. Notes are kept encrypted.
CREATE TABLE counselling_cases (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    student_id        BIGINT         NOT NULL REFERENCES students (id),
    concern           VARCHAR(10)    NOT NULL CHECK (concern IN ('ACADEMIC', 'FINANCIAL', 'HEALTH', 'FAMILY', 'PERSONAL', 'OTHER')),
    self_referred     BOOLEAN        NOT NULL,
    referred_by_name  VARCHAR(150),
    request_text_enc  TEXT,                                  -- what the student or the referrer wrote, encrypted
    contact_phone     VARCHAR(20),
    status            VARCHAR(8)     NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED')),
    next_session_at   TIMESTAMPTZ,
    opened_at         TIMESTAMPTZ    NOT NULL DEFAULT now(),
    closed_at         TIMESTAMPTZ
);
CREATE INDEX ix_counselling_cases_student ON counselling_cases (tenant_id, student_id);

CREATE TABLE counselling_notes (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  BIGINT         NOT NULL REFERENCES tenants (id),
    case_id    BIGINT         NOT NULL REFERENCES counselling_cases (id) ON DELETE CASCADE,
    note_enc   TEXT           NOT NULL,
    by_name    VARCHAR(150)   NOT NULL,
    at         TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- ===================================================================================================== procurement
CREATE TABLE suppliers (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT         NOT NULL REFERENCES tenants (id),
    name        VARCHAR(150)   NOT NULL,
    tin         VARCHAR(20),
    phone       VARCHAR(20),
    email       VARCHAR(120),
    address     VARCHAR(300),
    supplies    VARCHAR(200),                       -- what they supply
    active      BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_suppliers_name ON suppliers (tenant_id, lower(name));

-- A department's request to buy; endorsed by its Head, approved by the Bursar (funds) — never by the person who asked
CREATE TABLE purchase_requisitions (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT         NOT NULL REFERENCES tenants (id),
    req_no             VARCHAR(40)    NOT NULL,
    department_id      BIGINT         REFERENCES departments (id),
    title              VARCHAR(200)   NOT NULL,
    justification      VARCHAR(1000)  NOT NULL,
    needed_by          DATE,
    estimated_total    NUMERIC(16,2)  NOT NULL DEFAULT 0,
    status             VARCHAR(12)    NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'ENDORSED', 'APPROVED', 'REJECTED', 'ORDERED', 'CANCELLED')),
    requested_by       BIGINT,
    requested_by_name  VARCHAR(150)   NOT NULL,
    requested_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    endorsed_by        BIGINT,
    endorsed_by_name   VARCHAR(150),
    endorsed_at        TIMESTAMPTZ,
    approved_by        BIGINT,
    approved_by_name   VARCHAR(150),
    approved_at        TIMESTAMPTZ,
    decision_note      VARCHAR(500),
    CONSTRAINT uq_purchase_requisitions UNIQUE (tenant_id, req_no)
);

CREATE TABLE requisition_lines (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    requisition_id    BIGINT         NOT NULL REFERENCES purchase_requisitions (id) ON DELETE CASCADE,
    description       VARCHAR(300)   NOT NULL,
    quantity          NUMERIC(12,2)  NOT NULL CHECK (quantity > 0),
    unit              VARCHAR(20)    NOT NULL,
    estimated_cost    NUMERIC(14,2)  NOT NULL CHECK (estimated_cost >= 0),     -- per unit
    store_item_id     BIGINT         REFERENCES store_items (id),              -- received into this stores item
    asset_category    VARCHAR(20)    CHECK (asset_category IN ('FURNITURE', 'ICT', 'LAB_EQUIPMENT', 'WORKSHOP', 'VEHICLE', 'BUILDING', 'LAND',
                                                               'OFFICE_EQUIPMENT', 'OTHER')),   -- or entered in the asset register
    sort_order        SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_requisition_lines_target CHECK (store_item_id IS NULL OR asset_category IS NULL)
);

-- Quotations from suppliers for a requisition; one is chosen
CREATE TABLE quotations (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    requisition_id   BIGINT         NOT NULL REFERENCES purchase_requisitions (id) ON DELETE CASCADE,
    supplier_id      BIGINT         NOT NULL REFERENCES suppliers (id),
    amount           NUMERIC(16,2)  NOT NULL CHECK (amount > 0),
    delivery_days    SMALLINT       CHECK (delivery_days >= 0),
    reference        VARCHAR(60),
    file_name        VARCHAR(200),
    content_type     VARCHAR(60),
    data             BYTEA,
    chosen           BOOLEAN        NOT NULL DEFAULT FALSE,
    choice_note      VARCHAR(500),
    created_by_name  VARCHAR(150)   NOT NULL,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_quotations UNIQUE (requisition_id, supplier_id)
);

-- Local purchase orders
CREATE TABLE purchase_orders (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id        BIGINT         NOT NULL REFERENCES tenants (id),
    lpo_no           VARCHAR(40)    NOT NULL,
    requisition_id   BIGINT         NOT NULL REFERENCES purchase_requisitions (id),
    supplier_id      BIGINT         NOT NULL REFERENCES suppliers (id),
    quotation_id     BIGINT         REFERENCES quotations (id),
    total            NUMERIC(16,2)  NOT NULL,
    deliver_by       DATE,
    terms            VARCHAR(500),
    status           VARCHAR(14)    NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('ISSUED', 'PART_RECEIVED', 'RECEIVED', 'CANCELLED')),
    issued_by        BIGINT,
    issued_by_name   VARCHAR(150)   NOT NULL,
    issued_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    cancel_note      VARCHAR(300),
    CONSTRAINT uq_purchase_orders UNIQUE (tenant_id, lpo_no)
);

CREATE TABLE purchase_order_lines (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           BIGINT         NOT NULL REFERENCES tenants (id),
    order_id            BIGINT         NOT NULL REFERENCES purchase_orders (id) ON DELETE CASCADE,
    requisition_line_id BIGINT         REFERENCES requisition_lines (id),
    description         VARCHAR(300)   NOT NULL,
    quantity            NUMERIC(12,2)  NOT NULL CHECK (quantity > 0),
    unit                VARCHAR(20)    NOT NULL,
    unit_price          NUMERIC(14,2)  NOT NULL CHECK (unit_price >= 0),
    received            NUMERIC(12,2)  NOT NULL DEFAULT 0 CHECK (received >= 0),
    store_item_id       BIGINT         REFERENCES store_items (id),
    asset_category      VARCHAR(20),
    sort_order          SMALLINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_purchase_order_lines_received CHECK (received <= quantity)
);

-- Goods received against an order (each delivery)
CREATE TABLE goods_receipts (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT         NOT NULL REFERENCES tenants (id),
    grn_no            VARCHAR(40)    NOT NULL,
    order_id          BIGINT         NOT NULL REFERENCES purchase_orders (id),
    delivery_note     VARCHAR(60),
    invoice_ref       VARCHAR(60),
    note              VARCHAR(500),
    lines             JSONB          NOT NULL,     -- [{"orderLineId","description","quantity","unitPrice","storeItem","assetTags":[...]}]
    value             NUMERIC(16,2)  NOT NULL,
    received_by_name  VARCHAR(150)   NOT NULL,
    received_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_goods_receipts UNIQUE (tenant_id, grn_no)
);
CREATE INDEX ix_goods_receipts_order ON goods_receipts (tenant_id, order_id);

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['payroll_settings', 'staff_pay', 'staff_pay_items', 'payroll_runs', 'payslips',
                             'evaluation_questions', 'evaluation_rounds', 'evaluation_done', 'evaluation_responses',
                             'discipline_cases', 'counselling_cases', 'counselling_notes',
                             'suppliers', 'purchase_requisitions', 'requisition_lines', 'quotations', 'purchase_orders', 'purchase_order_lines',
                             'goods_receipts']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;
