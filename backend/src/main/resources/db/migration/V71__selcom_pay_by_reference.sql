-- ===========================================================================================================================
-- V71: paying Msingi's invoices by reference through Selcom as well as ClickPesa. The same column holds the number payers enter
-- before the reference: ClickPesa's 4-digit BillPay-Namba, or Selcom Pay's merchant number (up to 12 digits). With Selcom, the
-- payer chooses "Lipa kwa Selcom Pay" in any mobile wallet, bank app or at a Selcom agent, enters the merchant number and then the
-- invoice's payment reference; Selcom asks CIMS to look up and validate the reference and reports the payment (C2B).
-- ===========================================================================================================================
ALTER TABLE platform_collection_account ALTER COLUMN billpay_namba TYPE VARCHAR(12);
