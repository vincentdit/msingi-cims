-- ===========================================================================================================================
-- V69: bank and mobile payments to Msingi without anyone recording them.
--   * billpay_namba: Msingi's 4-digit ClickPesa BillPay-Namba. With it, every Msingi invoice carries a BillPay control number
--     (the namba followed by the invoice's payment reference) that can be paid from M-Pesa, Mixx by Yas, Airtel Money,
--     HaloPesa, CRDB SimBanking and CRDB agents; ClickPesa reports each payment and CIMS records it.
--   * billing_statement_imports: bank statements Msingi uploads for transfers made straight to its bank accounts; lines quoting
--     an invoice's payment reference are recorded, the rest listed.
-- ===========================================================================================================================
ALTER TABLE platform_collection_account ADD COLUMN billpay_namba VARCHAR(6);

CREATE TABLE billing_statement_imports (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_name       VARCHAR(200)  NOT NULL,
    provider        VARCHAR(20)   NOT NULL,                     -- the bank (PaymentProvider code)
    total_lines     INT           NOT NULL,
    recorded        INT           NOT NULL,
    already         INT           NOT NULL,
    unmatched       INT           NOT NULL,
    result          TEXT          NOT NULL,                     -- JSON: the lines and what became of each
    imported_by_name VARCHAR(150),
    imported_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
