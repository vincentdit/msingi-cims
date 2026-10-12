-- =====================================================================
-- CIMS V27: applicants may change their details, programme choices and
-- other qualifications after submitting, while the window is open and
-- before selection starts. When it was last changed is kept for the
-- Admissions Office.
-- =====================================================================

ALTER TABLE applications ADD COLUMN amended_at TIMESTAMPTZ;
