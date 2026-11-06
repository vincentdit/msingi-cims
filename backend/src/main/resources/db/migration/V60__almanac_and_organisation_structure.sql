-- ===========================================================================================================================
-- Almanac and organisation structure (stakeholders' comments on the pilot):
--  * the almanac is the year's calendar of the whole institution: the academic calendar's key dates plus meetings of the
--    Governing Board and committees, events and activities, public holidays and deadlines, each for everyone, staff only
--    or students, with where it happens and the office responsible. Once published it shows on the student portal and on
--    the institution's public site (what is for everyone);
--  * the organisation structure is built by the institution itself: units (board, offices, directorates, faculties,
--    departments, units, sections, committees) in a tree, each with its positions and who holds them. It can be published
--    on the public site.
-- ===========================================================================================================================

ALTER TABLE calendar_events DROP CONSTRAINT IF EXISTS calendar_events_kind_check;
ALTER TABLE calendar_events ADD CONSTRAINT calendar_events_kind_check CHECK (kind IN (
    'REGISTRATION', 'TEACHING', 'MARKS_ENTRY', 'EXAMINATIONS', 'RESULTS', 'HOLIDAY', 'GRADUATION', 'OTHER',
    'MEETING', 'ACTIVITY', 'DEADLINE'));
ALTER TABLE calendar_events
    ADD COLUMN audience     VARCHAR(10)  NOT NULL DEFAULT 'ALL' CHECK (audience IN ('ALL', 'STAFF', 'STUDENTS')),
    ADD COLUMN venue        VARCHAR(150),
    ADD COLUMN responsible  VARCHAR(150),
    ADD COLUMN description  VARCHAR(500);

ALTER TABLE academic_years
    ADD COLUMN almanac_published_at  TIMESTAMPTZ,
    ADD COLUMN almanac_published_by  VARCHAR(200);

CREATE TABLE org_units (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL REFERENCES tenants (id),
    parent_id      BIGINT        REFERENCES org_units (id),
    kind           VARCHAR(20)   NOT NULL CHECK (kind IN ('BOARD', 'COMMITTEE', 'OFFICE', 'DIRECTORATE', 'FACULTY', 'DEPARTMENT', 'UNIT',
                                                          'SECTION', 'CAMPUS')),
    name           VARCHAR(150)  NOT NULL,
    short_name     VARCHAR(30),
    department_id  BIGINT        REFERENCES departments (id) ON DELETE SET NULL,
    -- an advisory body (a committee) is drawn beside the unit it reports to, joined by a dashed line
    advisory       BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order     INTEGER       NOT NULL DEFAULT 0,
    description    VARCHAR(500),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_org_units_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX ix_org_units_parent ON org_units (tenant_id, parent_id, sort_order);

CREATE TABLE org_positions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       BIGINT        NOT NULL REFERENCES tenants (id),
    unit_id         BIGINT        NOT NULL REFERENCES org_units (id) ON DELETE CASCADE,
    title           VARCHAR(150)  NOT NULL,
    -- the head of the unit is shown first, in the unit's box
    head            BOOLEAN       NOT NULL DEFAULT FALSE,
    -- who holds it: a CIMS staff account, or a name (board members and others without an account); empty when vacant
    holder_user_id  BIGINT        REFERENCES user_accounts (id) ON DELETE SET NULL,
    holder_name     VARCHAR(150),
    acting          BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order      INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX ix_org_positions_unit ON org_positions (tenant_id, unit_id, sort_order);

CREATE TABLE org_settings (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      BIGINT        NOT NULL UNIQUE REFERENCES tenants (id),
    published_at   TIMESTAMPTZ,
    published_by   VARCHAR(200),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['org_units', 'org_positions', 'org_settings']
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())', t);
    END LOOP;
END $$;
