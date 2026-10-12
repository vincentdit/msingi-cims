-- =====================================================================
-- CIMS V26: the Admissions Office checks qualifications applicants
-- declare (NVA awards, other awards, NTA awards typed in) against the
-- certificates: verified, or not accepted with a reason the applicant
-- sees. Results from NECTA and NACTVET are verified at source.
-- =====================================================================

ALTER TABLE applicant_qualifications
    ADD COLUMN check_status      VARCHAR(10) NOT NULL DEFAULT 'PENDING'
        CHECK (check_status IN ('PENDING', 'VERIFIED', 'REJECTED')),
    ADD COLUMN check_note        VARCHAR(500),
    ADD COLUMN checked_by_name   VARCHAR(150);

DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        UPDATE applicant_qualifications SET check_status = 'VERIFIED' WHERE verified;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
