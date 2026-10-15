-- FR-IPT-03: the workplace supervisor assesses the student online through a link sent by the institution.
-- BR-31 / FR-GRD-05: alumni contacts and graduate tracer surveys.

CREATE TABLE ipt_assessment_links (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    placement_id      BIGINT        NOT NULL REFERENCES ipt_placements (id) ON DELETE CASCADE,
    token_hash        CHAR(64)      NOT NULL UNIQUE,
    expires_at        TIMESTAMPTZ   NOT NULL,
    sent_to_phone     VARCHAR(20),
    sent_to_email     VARCHAR(150),
    created_by_name   VARCHAR(200)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    used_at           TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ
);
CREATE INDEX ix_ipt_assessment_links_placement ON ipt_assessment_links (tenant_id, placement_id);

ALTER TABLE ipt_assessments ADD COLUMN criteria JSONB;                     -- the workplace supervisor's ratings, when given online
ALTER TABLE ipt_assessments ADD COLUMN source VARCHAR(6) NOT NULL DEFAULT 'STAFF' CHECK (source IN ('STAFF', 'LINK'));

-- What the institution knows about its graduates now (kept up to date by staff, the graduate, or a survey answer).
CREATE TABLE alumni_profiles (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    student_id         BIGINT        NOT NULL UNIQUE REFERENCES students (id),
    phone              VARCHAR(20),
    email              VARCHAR(120),
    employment_status  VARCHAR(16)   CHECK (employment_status IN ('EMPLOYED', 'SELF_EMPLOYED', 'FURTHER_STUDIES', 'SEEKING', 'NOT_SEEKING')),
    employer           VARCHAR(150),
    job_title          VARCHAR(120),
    sector             VARCHAR(60),
    region             VARCHAR(60),
    consent_contact    BOOLEAN       NOT NULL DEFAULT true,
    updated_via        VARCHAR(8)    NOT NULL CHECK (updated_via IN ('STAFF', 'SURVEY', 'PORTAL')),
    updated_by_name    VARCHAR(200),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE TABLE tracer_surveys (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id         BIGINT        NOT NULL REFERENCES tenants (id),
    title             VARCHAR(200)  NOT NULL,
    intro             VARCHAR(1000),
    graduation_id     BIGINT        REFERENCES graduations (id),
    programme_id      BIGINT        REFERENCES programmes (id),
    questions         JSONB         NOT NULL,
    status            VARCHAR(8)    NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'OPEN', 'CLOSED')),
    closes_on         DATE,
    created_by_name   VARCHAR(200)  NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    opened_at         TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ
);

CREATE TABLE tracer_invitations (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants (id),
    survey_id      BIGINT        NOT NULL REFERENCES tracer_surveys (id) ON DELETE CASCADE,
    student_id     BIGINT        NOT NULL REFERENCES students (id),
    token_hash     CHAR(64)      NOT NULL UNIQUE,
    sent_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    reminded_at    TIMESTAMPTZ,
    responded_at   TIMESTAMPTZ,
    CONSTRAINT uq_tracer_invitations UNIQUE (survey_id, student_id)
);

CREATE TABLE tracer_responses (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants (id),
    survey_id      BIGINT        NOT NULL REFERENCES tracer_surveys (id) ON DELETE CASCADE,
    student_id     BIGINT        NOT NULL REFERENCES students (id),
    answers        JSONB         NOT NULL,
    submitted_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_tracer_responses UNIQUE (survey_id, student_id)
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['ipt_assessment_links', 'alumni_profiles', 'tracer_surveys', 'tracer_invitations', 'tracer_responses']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
