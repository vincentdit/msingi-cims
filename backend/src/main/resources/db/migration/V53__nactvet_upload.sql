-- NACTVET admission verification as NOAS uses NACTVET's API: an API batch is uploaded one programme at a time under the
-- payment reference of the verification fee, and outcomes are pulled from NACTVET's feedback by application year and intake
-- (kept in external_ref as "<year>/<intake>", e.g. 2026/SEPTEMBER).
ALTER TABLE nactvet_submissions
    ADD COLUMN payment_reference VARCHAR(60),
    ADD COLUMN nactvet_response  VARCHAR(2000);

COMMENT ON COLUMN nactvet_submissions.external_ref IS 'API batch: NACTVET application year and intake of the feedback, e.g. 2026/SEPTEMBER';
COMMENT ON COLUMN nactvet_submissions.payment_reference IS 'API batch: payment reference (control number) of the NACTVET verification fee';
COMMENT ON COLUMN nactvet_submissions.nactvet_response IS 'API batch: NACTVET''s answer to each programme upload';
