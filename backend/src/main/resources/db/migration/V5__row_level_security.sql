-- =====================================================================
-- CIMS V5: row-level security (tenant isolation in the database)
--
-- The application sets the tenant for every transaction:
--     SELECT set_config('app.tenant_id', '<id>', true);
-- Rows of other institutions are invisible and cannot be written,
-- even if application code has a bug. FORCE makes the policy apply to
-- the table owner too. Superusers always bypass RLS, so the application
-- must never connect as a superuser.
-- =====================================================================

CREATE OR REPLACE FUNCTION cims_current_tenant() RETURNS BIGINT
    LANGUAGE sql STABLE AS
$$ SELECT NULLIF(current_setting('app.tenant_id', true), '')::BIGINT $$;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'user_accounts', 'otp_codes', 'number_sequences', 'audit_logs',
        'academic_years', 'intakes', 'departments', 'programmes',
        'invoices', 'invoice_lines', 'control_numbers', 'payments',
        'admission_windows', 'window_programmes', 'applicants',
        'applicant_qualifications', 'qualification_subjects',
        'applications', 'application_choices'
    ]
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY tenant_isolation ON %I USING (tenant_id = cims_current_tenant()) WITH CHECK (tenant_id = cims_current_tenant())',
            t);
    END LOOP;
END $$;

-- user_roles has no tenant_id: a role row is visible only if its user is visible.
ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_roles
    USING (EXISTS (SELECT 1 FROM user_accounts u WHERE u.id = user_roles.user_id))
    WITH CHECK (EXISTS (SELECT 1 FROM user_accounts u WHERE u.id = user_roles.user_id));

-- Audit trail is append-only for application code.
CREATE OR REPLACE FUNCTION cims_reject_audit_change() RETURNS trigger LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION 'audit_logs is append-only'; END $$;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION cims_reject_audit_change();

-- Financial records cannot be deleted; corrections are reversals or credit notes.
CREATE OR REPLACE FUNCTION cims_reject_delete() RETURNS trigger LANGUAGE plpgsql AS
$$ BEGIN RAISE EXCEPTION '% rows cannot be deleted', TG_TABLE_NAME; END $$;
CREATE TRIGGER trg_invoices_no_delete BEFORE DELETE ON invoices FOR EACH ROW EXECUTE FUNCTION cims_reject_delete();
CREATE TRIGGER trg_payments_no_delete BEFORE DELETE ON payments FOR EACH ROW EXECUTE FUNCTION cims_reject_delete();
