-- =====================================================================
-- CIMS V22: applicants register with their NECTA Form Four (CSEE) index
-- number and year (BR-04, FR-ADM-02). Names and sex come from NECTA and
-- the CSEE results are stored at registration; one account per index
-- number and year in an institution.
-- =====================================================================

ALTER TABLE applicants
    ADD COLUMN necta_index_number VARCHAR(20),
    ADD COLUMN necta_exam_year    SMALLINT,
    ADD COLUMN names_from_necta   BOOLEAN NOT NULL DEFAULT FALSE;

CREATE UNIQUE INDEX uq_applicants_necta_index ON applicants (tenant_id, necta_index_number, necta_exam_year)
    WHERE necta_index_number IS NOT NULL;
