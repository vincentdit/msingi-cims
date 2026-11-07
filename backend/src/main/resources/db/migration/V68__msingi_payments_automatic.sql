-- ===========================================================================================================================
-- V68: Msingi's billing without human steps.
--   * How institutions may pay each kind of Msingi invoice: by mobile money, by bank or both (pay_methods, per kind of invoice;
--     a kind not listed is BOTH). Invoices, emails, PDFs and the Buy SMS window show only the accounts allowed, and a phone
--     prompt is offered only where mobile money is.
--   * Invoices prepared by the daily job are issued at once with their payment reference (control number): auto_issue becomes the
--     default and is switched on for the existing contracts.
-- ===========================================================================================================================
ALTER TABLE platform_billing_settings
    ADD COLUMN pay_methods TEXT NOT NULL DEFAULT '{}';      -- JSON {"LICENCE":"BOTH","USAGE":"BANK","SMS":"MOBILE","OTHER":"BOTH"}

ALTER TABLE billing_contracts ALTER COLUMN auto_issue SET DEFAULT TRUE;
UPDATE billing_contracts SET auto_issue = TRUE WHERE auto_issue = FALSE;
