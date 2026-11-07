-- ===========================================================================================================================
-- V67: the balance of Msingi's account with the SMS provider, read every few hours, and the SMS institutions still hold in
-- their bundles: every institution's messages draw on that one account, so Msingi is warned before it runs short.
-- ===========================================================================================================================
ALTER TABLE platform_sms_settings
    ADD COLUMN last_balance       BIGINT,
    ADD COLUMN last_balance_at    TIMESTAMPTZ,
    ADD COLUMN outstanding_units  BIGINT,
    ADD COLUMN low_alert_at       TIMESTAMPTZ;
