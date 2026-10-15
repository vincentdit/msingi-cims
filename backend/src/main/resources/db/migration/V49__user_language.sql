-- The language each person uses CIMS in (BR-40): English or Kiswahili, chosen on the screen and kept with the account so it
-- follows them to another device. Empty until they choose (the browser's last choice, else English, applies).
ALTER TABLE user_accounts ADD COLUMN language VARCHAR(2) CHECK (language IN ('en', 'sw'));
