-- =====================================================================
-- CIMS V4: online admission
-- =====================================================================

CREATE TABLE admission_windows (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    academic_year_id  BIGINT        NOT NULL REFERENCES academic_years (id),
    intake_id         BIGINT        NOT NULL REFERENCES intakes (id),
    name              VARCHAR(150)  NOT NULL,
    opens_at          TIMESTAMPTZ   NOT NULL,
    closes_at         TIMESTAMPTZ   NOT NULL,
    application_fee   NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (application_fee >= 0),
    max_choices       SMALLINT      NOT NULL DEFAULT 3 CHECK (max_choices BETWEEN 1 AND 10),
    status            VARCHAR(12)   NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    instructions      TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_admission_windows_dates CHECK (closes_at > opens_at)
);
CREATE INDEX ix_admission_windows_status ON admission_windows (tenant_id, status);

-- Programmes offered in a window, with capacity and NACTVET minimum entry rules (JSON rule set)
CREATE TABLE window_programmes (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenants (id),
    window_id         BIGINT      NOT NULL REFERENCES admission_windows (id),
    programme_id      BIGINT      NOT NULL REFERENCES programmes (id),
    capacity          INT         NOT NULL CHECK (capacity > 0),
    entry_rules       JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    version           BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_window_programmes UNIQUE (window_id, programme_id)
);

CREATE TABLE applicants (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               BIGINT       NOT NULL REFERENCES tenants (id),
    user_id                 BIGINT       NOT NULL UNIQUE REFERENCES user_accounts (id),
    first_name              VARCHAR(60)  NOT NULL,
    middle_name             VARCHAR(60),
    surname                 VARCHAR(60)  NOT NULL,
    sex                     CHAR(1)      CHECK (sex IN ('M', 'F')),
    date_of_birth           DATE,
    nationality             VARCHAR(60),
    phone                   VARCHAR(20)  NOT NULL,
    email                   VARCHAR(120),
    region                  VARCHAR(60),
    district                VARCHAR(60),
    postal_address          VARCHAR(200),
    disability              VARCHAR(120),
    next_of_kin_name        VARCHAR(150),
    next_of_kin_phone       VARCHAR(20),
    next_of_kin_relation    VARCHAR(40),
    privacy_consent_at      TIMESTAMPTZ  NOT NULL,
    privacy_notice_version  VARCHAR(20)  NOT NULL,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version                 BIGINT       NOT NULL DEFAULT 0
);

CREATE TABLE applicant_qualifications (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenants (id),
    applicant_id    BIGINT      NOT NULL REFERENCES applicants (id) ON DELETE CASCADE,
    type            VARCHAR(6)  NOT NULL CHECK (type IN ('CSEE', 'ACSEE', 'NTA', 'NVA')),
    index_number    VARCHAR(40) NOT NULL,       -- NECTA index no., AVN or NVA certificate no.
    exam_year       SMALLINT,
    award_level     SMALLINT,
    centre_name     VARCHAR(150),
    division        VARCHAR(10),
    points          SMALLINT,
    candidate_name  VARCHAR(200),
    source          VARCHAR(10) NOT NULL CHECK (source IN ('NECTA', 'NACTVET', 'MANUAL')),
    verified        BOOLEAN     NOT NULL DEFAULT FALSE,
    verified_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_applicant_qualifications UNIQUE (applicant_id, type, index_number)
);

CREATE TABLE qualification_subjects (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT      NOT NULL REFERENCES tenants (id),
    qualification_id  BIGINT      NOT NULL REFERENCES applicant_qualifications (id) ON DELETE CASCADE,
    subject_code      VARCHAR(20) NOT NULL,
    subject_name      VARCHAR(100) NOT NULL,
    grade             VARCHAR(3)  NOT NULL
);
CREATE INDEX ix_qualification_subjects_q ON qualification_subjects (qualification_id);

CREATE TABLE applications (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT      NOT NULL REFERENCES tenants (id),
    applicant_id    BIGINT      NOT NULL REFERENCES applicants (id),
    window_id       BIGINT      NOT NULL REFERENCES admission_windows (id),
    reference_no    VARCHAR(40),
    status          VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PAYMENT_PENDING', 'SUBMITTED', 'UNDER_REVIEW', 'SELECTED', 'NOT_SELECTED', 'WITHDRAWN')),
    fee_invoice_id  BIGINT      REFERENCES invoices (id),
    fee_paid        BOOLEAN     NOT NULL DEFAULT FALSE,
    submitted_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_applications_window UNIQUE (applicant_id, window_id)
);
CREATE UNIQUE INDEX uq_applications_reference ON applications (tenant_id, reference_no) WHERE reference_no IS NOT NULL;
CREATE INDEX ix_applications_status ON applications (tenant_id, window_id, status);

CREATE TABLE application_choices (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT      NOT NULL REFERENCES tenants (id),
    application_id       BIGINT      NOT NULL REFERENCES applications (id) ON DELETE CASCADE,
    window_programme_id  BIGINT      NOT NULL REFERENCES window_programmes (id),
    choice_order         SMALLINT    NOT NULL CHECK (choice_order BETWEEN 1 AND 10),
    eligible             BOOLEAN     NOT NULL,
    eligibility_detail   JSONB       NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_choices_order UNIQUE (application_id, choice_order),
    CONSTRAINT uq_application_choices_programme UNIQUE (application_id, window_programme_id)
);
