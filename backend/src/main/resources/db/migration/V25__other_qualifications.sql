-- =====================================================================
-- CIMS V25: additional and other qualifications after the fee, as in
-- NOAS: NTA awards looked up at NACTVET by registration number and
-- graduation year (programme, institution, GPA, class of award and
-- modules), and other non-NTA awards declared by the applicant.
-- =====================================================================

ALTER TABLE applicant_qualifications DROP CONSTRAINT applicant_qualifications_type_check;
ALTER TABLE applicant_qualifications ADD CONSTRAINT applicant_qualifications_type_check
    CHECK (type IN ('CSEE', 'ACSEE', 'NTA', 'NVA', 'OTHER'));

ALTER TABLE applicant_qualifications
    ADD COLUMN award_name   VARCHAR(200),        -- programme of an NTA award, or the name of another award
    ADD COLUMN institution  VARCHAR(200),        -- awarding institution
    ADD COLUMN gpa          NUMERIC(4,2),
    ADD COLUMN class_award  VARCHAR(60),
    ADD COLUMN other_level  VARCHAR(20)          -- level of another award: CERTIFICATE, DIPLOMA, DEGREE, ...
        CHECK (other_level IS NULL OR other_level IN ('CERTIFICATE', 'DIPLOMA', 'ADVANCED_DIPLOMA', 'DEGREE', 'MASTERS', 'OTHER'));

ALTER TABLE qualification_subjects ALTER COLUMN subject_name TYPE VARCHAR(200);
ALTER TABLE qualification_subjects ADD COLUMN credits SMALLINT;
