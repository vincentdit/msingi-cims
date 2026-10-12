-- =====================================================================
-- CIMS V23: fuller addresses (ward, village/street) chosen from
-- Tanzania's administrative areas, and fuller next-of-kin details
-- (email, address, region, district, ward, village/street), as in NOAS.
-- The same fields pass from the applicant to the student at hand-over.
-- =====================================================================

ALTER TABLE applicants
    ADD COLUMN ward                  VARCHAR(80),
    ADD COLUMN street                VARCHAR(120),
    ADD COLUMN next_of_kin_email     VARCHAR(120),
    ADD COLUMN next_of_kin_address   VARCHAR(200),
    ADD COLUMN next_of_kin_region    VARCHAR(60),
    ADD COLUMN next_of_kin_district  VARCHAR(60),
    ADD COLUMN next_of_kin_ward      VARCHAR(80),
    ADD COLUMN next_of_kin_street    VARCHAR(120);

ALTER TABLE students
    ADD COLUMN ward                  VARCHAR(80),
    ADD COLUMN street                VARCHAR(120),
    ADD COLUMN next_of_kin_email     VARCHAR(120),
    ADD COLUMN next_of_kin_address   VARCHAR(200),
    ADD COLUMN next_of_kin_region    VARCHAR(60),
    ADD COLUMN next_of_kin_district  VARCHAR(60),
    ADD COLUMN next_of_kin_ward      VARCHAR(80),
    ADD COLUMN next_of_kin_street    VARCHAR(120);
