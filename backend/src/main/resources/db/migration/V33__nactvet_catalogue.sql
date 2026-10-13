-- =====================================================================
-- CIMS V33: the NACTVET institution/programme catalogue (as NOAS uses it).
-- The platform pulls NACTVET's api-out-service/institutionprogrammeall:
-- every registered institution with its accredited programmes, levels,
-- durations, capacities, fees and entry requirements. Msingi onboards
-- institutions from it and imports their programmes.
-- Platform tables: they belong to no institution (no row-level security).
-- =====================================================================

CREATE TABLE nactvet_institutions (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nactvet_key          VARCHAR(80)   NOT NULL UNIQUE,   -- NACTVET institution id, or its code or name when the id is missing
    nactvet_id           VARCHAR(40),
    code                 VARCHAR(64),
    name                 VARCHAR(200)  NOT NULL,
    registration_number  VARCHAR(40),
    region               VARCHAR(60),
    district             VARCHAR(80),
    ownership            VARCHAR(40),
    mobile_number        VARCHAR(30),
    active               BOOLEAN       NOT NULL DEFAULT TRUE,   -- false when NACTVET stopped listing it
    first_seen_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    tenant_id            BIGINT        UNIQUE REFERENCES tenants (id)   -- the CIMS institution onboarded from it
);
CREATE INDEX ix_nactvet_institutions_name ON nactvet_institutions (lower(name));

CREATE TABLE nactvet_programmes (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    institution_id           BIGINT        NOT NULL REFERENCES nactvet_institutions (id),
    programme_key            VARCHAR(80)   NOT NULL,   -- NACTVET programme id, or course code, or name
    nactvet_programme_id     VARCHAR(40),
    course_code              VARCHAR(30),
    name                     VARCHAR(200)  NOT NULL,
    programme_level          VARCHAR(20),
    nta_level                SMALLINT,
    board_name               VARCHAR(150),
    board_code               VARCHAR(30),
    category_name            VARCHAR(150),
    category_code            VARCHAR(30),
    programme_fee            VARCHAR(40),
    entry_requirement        TEXT,
    selection_criteria       VARCHAR(1000),
    necta_requirement        VARCHAR(1000),
    nta_requirement          VARCHAR(1000),
    nta_entry_min_gpa        NUMERIC(3, 1),
    nta_entry_holder_level   SMALLINT,          -- the NTA award an applicant must already hold (4 or 5), if any
    duration_years           SMALLINT,
    male_capacity            INT,
    female_capacity          INT,
    total_capacity           INT,
    active                   BOOLEAN       NOT NULL DEFAULT TRUE,
    first_seen_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_seen_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_nactvet_programmes UNIQUE (institution_id, programme_key)
);

CREATE TABLE nactvet_catalogue_syncs (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    started_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    status            VARCHAR(10)   NOT NULL CHECK (status IN ('RUNNING', 'DONE', 'FAILED')),
    mode              VARCHAR(10)   NOT NULL CHECK (mode IN ('LIVE', 'SIMULATED')),
    offerings         INT,
    institutions      INT,
    programmes        INT,
    new_institutions  INT,
    new_programmes    INT,
    withdrawn         INT,
    error             VARCHAR(500),
    started_by        VARCHAR(150)  NOT NULL
);

ALTER TABLE integration_calls DROP CONSTRAINT integration_calls_system_check;
ALTER TABLE integration_calls ADD CONSTRAINT integration_calls_system_check
    CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'NACTVET_CATALOGUE', 'GEPG', 'SMS'));
