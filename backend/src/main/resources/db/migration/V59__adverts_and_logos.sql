-- ===========================================================================================================================
-- Advertising programmes and logos:
--  * the institution's logo (shown on its programme pages, share previews and advert posters) is kept with its photos;
--  * selling points per programme offered in an admission window ("Hostel bure", practicals at the district hospital…),
--    one per line, shown on the programme page and the poster;
--  * Msingi's own logo on its invoices, receipts and statements (a built-in logo is used until one is uploaded).
-- ===========================================================================================================================

ALTER TABLE institution_photos DROP CONSTRAINT IF EXISTS institution_photos_kind_check;
ALTER TABLE institution_photos ADD CONSTRAINT institution_photos_kind_check CHECK (kind IN ('CAMPUS', 'LOGO'));

ALTER TABLE window_programmes ADD COLUMN highlights VARCHAR(500);

ALTER TABLE platform_billing_settings
    ADD COLUMN logo BYTEA,
    ADD COLUMN logo_type VARCHAR(20) CHECK (logo_type IN ('image/png', 'image/jpeg'));
