-- =====================================================================
-- CIMS V30: the Institution Administrator manages staff accounts and
-- their roles (as NOAS user management): temporary passwords that must
-- be changed at first sign-in, and signing a user out everywhere when
-- the account is disabled, its roles change or its password is reset.
-- =====================================================================

ALTER TABLE user_accounts
    ADD COLUMN must_change_password BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN sessions_valid_after TIMESTAMPTZ,
    ADD COLUMN job_title            VARCHAR(120),
    ADD COLUMN created_by_name      VARCHAR(150);
