-- =====================================================================
-- CIMS V28: the level a programme admits at (e.g. an Ordinary Diploma,
-- NTA 4–6, admits Form Four leavers at NTA 4), used for the NOAS rule
-- that applicants apply only at the level their highest qualification
-- leads to. Empty means worked out from the award level and duration.
-- =====================================================================

ALTER TABLE programmes ADD COLUMN entry_level SMALLINT CHECK (entry_level IS NULL OR entry_level BETWEEN 1 AND 8);
