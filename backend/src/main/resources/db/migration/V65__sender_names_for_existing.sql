-- Sender names entered on the Messaging settings before sender names were followed (V64) become requests for Msingi to
-- register, so they appear in the console's SMS page. Names TCRA would refuse (spaces, symbols, too short) are left for the
-- institution to correct. Row-level security applies to migrations too, so each institution is handled in turn.
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t.id::text, true);
        INSERT INTO sms_sender_names (tenant_id, sender_id, requested_by_name, note)
        SELECT m.tenant_id, trim(m.sender_id), 'CIMS (name already on the Messaging settings)', NULL
        FROM messaging_settings m
        WHERE m.sender_id IS NOT NULL AND trim(m.sender_id) ~ '^(?=.*[A-Za-z])[A-Za-z0-9]{3,11}$'
        ON CONFLICT (tenant_id, sender_id) DO NOTHING;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;
