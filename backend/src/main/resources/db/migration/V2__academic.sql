-- =====================================================================
-- CIMS V2: academic structure
-- =====================================================================

CREATE TABLE academic_years (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants (id),
    code        VARCHAR(9)  NOT NULL,           -- e.g. 2027/2028
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    is_current  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_academic_years UNIQUE (tenant_id, code),
    CONSTRAINT ck_academic_years_dates CHECK (end_date > start_date)
);

CREATE TABLE intakes (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20) NOT NULL,           -- e.g. SEPTEMBER, MARCH
    name        VARCHAR(60) NOT NULL,
    start_month SMALLINT    NOT NULL CHECK (start_month BETWEEN 1 AND 12),
    active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_intakes UNIQUE (tenant_id, code)
);

CREATE TABLE departments (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenants (id),
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_departments UNIQUE (tenant_id, code)
);

CREATE TABLE programmes (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id            BIGINT       NOT NULL REFERENCES tenants (id),
    department_id        BIGINT       NOT NULL REFERENCES departments (id),
    code                 VARCHAR(30)  NOT NULL,
    nactvet_code         VARCHAR(30),
    name                 VARCHAR(200) NOT NULL,
    award_family         VARCHAR(3)   NOT NULL CHECK (award_family IN ('NVA', 'NTA')),
    award_level          SMALLINT     NOT NULL,
    award_name           VARCHAR(120) NOT NULL,
    duration_semesters   SMALLINT     NOT NULL CHECK (duration_semesters BETWEEN 1 AND 16),
    accredited_capacity  INT          CHECK (accredited_capacity IS NULL OR accredited_capacity > 0),
    description          TEXT,
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_programmes UNIQUE (tenant_id, code),
    CONSTRAINT ck_programmes_level CHECK (
        (award_family = 'NVA' AND award_level BETWEEN 1 AND 3) OR
        (award_family = 'NTA' AND award_level BETWEEN 4 AND 8))
);
CREATE INDEX ix_programmes_department ON programmes (department_id);
