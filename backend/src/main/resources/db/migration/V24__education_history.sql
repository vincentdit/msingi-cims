-- =====================================================================
-- CIMS V24: education history on the applicant profile, as in NOAS:
-- primary school chosen from NECTA's PSLE school list (or typed), and
-- the O-level and A-level secondary schools, taken from the NECTA CSEE
-- and ACSEE results where the applicant has them. Carried to students.
-- =====================================================================

ALTER TABLE applicants
    ADD COLUMN primary_school       VARCHAR(150),
    ADD COLUMN primary_school_code  VARCHAR(20),
    ADD COLUMN olevel_school        VARCHAR(150),
    ADD COLUMN olevel_from_necta    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN alevel_school        VARCHAR(150),
    ADD COLUMN alevel_from_necta    BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE students
    ADD COLUMN primary_school       VARCHAR(150),
    ADD COLUMN primary_school_code  VARCHAR(20),
    ADD COLUMN olevel_school        VARCHAR(150),
    ADD COLUMN alevel_school        VARCHAR(150);

-- schools of results already fetched from NECTA (the earliest CSEE / ACSEE sitting); row-level
-- security applies to migrations too, so each institution is updated in turn
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        UPDATE applicants a SET olevel_school = q.centre_name, olevel_from_necta = TRUE
        FROM (SELECT DISTINCT ON (applicant_id) applicant_id, centre_name FROM applicant_qualifications
              WHERE type = 'CSEE' AND source = 'NECTA' AND centre_name IS NOT NULL ORDER BY applicant_id, exam_year, id) q
        WHERE q.applicant_id = a.id;
        UPDATE applicants a SET alevel_school = q.centre_name, alevel_from_necta = TRUE
        FROM (SELECT DISTINCT ON (applicant_id) applicant_id, centre_name FROM applicant_qualifications
              WHERE type = 'ACSEE' AND source = 'NECTA' AND centre_name IS NOT NULL ORDER BY applicant_id, exam_year, id) q
        WHERE q.applicant_id = a.id;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
