-- Curricula from NACTVET (api-out-service/curriculum-details).

-- NACTVET's programme id in full (40 characters): programmes.nactvet_code keeps only 30, too short to ask NACTVET with.
ALTER TABLE programmes ADD COLUMN nactvet_programme_id VARCHAR(64);

-- Where a curriculum version came from: NACTVET's curriculum code and when it was fetched
ALTER TABLE curricula ADD COLUMN nactvet_curriculum_code VARCHAR(60);
ALTER TABLE curricula ADD COLUMN nactvet_fetched_at TIMESTAMPTZ;

-- Each import of a programme's curriculum from NACTVET, with what NACTVET answered per level and semester
CREATE TABLE nactvet_curriculum_imports (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id          BIGINT        NOT NULL REFERENCES tenants (id),
    programme_id       BIGINT        NOT NULL REFERENCES programmes (id),
    curriculum_id      BIGINT        REFERENCES curricula (id) ON DELETE SET NULL,
    academic_year      VARCHAR(20)   NOT NULL,
    intake             VARCHAR(10)   NOT NULL,
    outcome            VARCHAR(10)   NOT NULL CHECK (outcome IN ('IMPORTED', 'FAILED')),
    slots              JSONB         NOT NULL,     -- [{"year","semester","level","curriculumCode","modules","message"}]
    modules_created    INTEGER       NOT NULL DEFAULT 0,
    modules_updated    INTEGER       NOT NULL DEFAULT 0,
    message            VARCHAR(1000),
    imported_by_name   VARCHAR(150)  NOT NULL,
    imported_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_nactvet_curriculum_imports ON nactvet_curriculum_imports (tenant_id, programme_id, imported_at DESC);
ALTER TABLE nactvet_curriculum_imports ENABLE ROW LEVEL SECURITY;
ALTER TABLE nactvet_curriculum_imports FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON nactvet_curriculum_imports USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant());

ALTER TABLE integration_calls DROP CONSTRAINT integration_calls_system_check;
ALTER TABLE integration_calls ADD CONSTRAINT integration_calls_system_check
    CHECK (system IN ('NECTA', 'NACTVET', 'NACTVET_AWARDS', 'NACTVET_CATALOGUE', 'NACTVET_CURRICULUM', 'NACTVET_SUBMISSION', 'GEPG', 'COLLECTION',
                      'SMS', 'HESLB', 'NIDA', 'MUSE', 'TAMISEMI', 'NHIF'));
