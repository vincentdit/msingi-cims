# Deploying CIMS

One server runs the whole stack with Docker Compose: PostgreSQL (WAL archiving on), the CIMS backend, the web app, an
nginx TLS proxy and an encrypted-backup service. Larger installations put PostgreSQL on a managed or dedicated server and
run two backend containers behind the proxy; nothing in CIMS keeps state in memory that two servers would need to share
(sessions are signed tokens, the SMS and email outboxes and scheduled jobs use database locks).

| Requirement | How it is met |
|---|---|
| NFR-SEC-03 TLS 1.2+ | `nginx/cims.conf`: TLS 1.2/1.3 only, HSTS, HTTP redirected to HTTPS |
| NFR-SEC-03 encryption at rest | NIN and authenticator secrets encrypted by CIMS (`CIMS_DATA_KEY`); backups encrypted (AES-256); put the Docker volumes on an encrypted disk (LUKS or the cloud provider's disk encryption) |
| NFR-SEC-05 isolation | The app connects as `cims`, which is not a superuser and has no BYPASSRLS (`db-init`); CIMS refuses to start otherwise |
| NFR-AVL-02 RPO 15 min / RTO 4 h | WAL archived every 15 minutes at most (`archive_timeout = 900`) and shipped every 5 minutes; restore runbook below |
| NFR-AVL-03 backups | Daily encrypted dump and base backup, 35 days; first of each month kept 12 months; copied to a second location (`BACKUP_OFFSITE_CMD`); restore tested quarterly |
| NFR-OPS-01 health, logs, metrics | `/actuator/health/liveness` and `/readiness` through the proxy for the uptime monitor; `/actuator/metrics` and `/actuator/prometheus` inside the network; all containers log to stdout for the log collector |

## First installation

```bash
git clone … cims && cd cims
cp deploy/.env.example deploy/.env           # fill in every CHANGE value
mkdir -p deploy/secrets deploy/certs
openssl rand -base64 48 > deploy/secrets/backup-passphrase   # keep a copy OFF the server: without it backups cannot be read
chmod 600 deploy/secrets/* deploy/.env
# TLS certificate: deploy/certs/fullchain.pem and privkey.pem (wildcard *.cims.co.tz, e.g. from Let's Encrypt DNS challenge)
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build
docker compose -f deploy/docker-compose.prod.yml logs -f backend   # "Production settings checked", then "Started CimsApplication"
```

With the `prod` profile CIMS will not start while a development-only setting remains (missing data key or sign-in keys,
default callback secrets, OTPs in responses, demo data, the test GePG keys); the log lists each one.

### Sign-in keys

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt.pem
openssl rsa -in jwt.pem -pubout -out jwt.pub
# one line each, without the BEGIN/END lines:
grep -v '^-----' jwt.pem | tr -d '\n'    # → CIMS_JWT_PRIVATE_KEY
grep -v '^-----' jwt.pub | tr -d '\n'    # → CIMS_JWT_PUBLIC_KEY
shred -u jwt.pem
```

### GePG on-boarding

1. Generate the institution's (or Msingi's, as system integrator) signing key in a PKCS#12 keystore:
   `keytool -genkeypair -alias <alias> -keyalg RSA -keysize 2048 -validity 1095 -keystore gepg-sp.p12 -storetype PKCS12`
2. Export the certificate (`keytool -exportcert …`) and send it to GePG with the on-boarding forms; GePG returns its public
   certificate, the SP code, sub SP code(s) and system id, and the UAT/production addresses.
3. Put `gepg-sp.p12` and GePG's certificate (`gepg-public.crt`) in `deploy/secrets`, set `CIMS_GEPG_*` in `.env`.
4. Each institution enters its SP code, sub SP code, system id and default GFS code under **Administration → Integrations**,
   and the GFS code of each fee item under Fees.
5. GePG must be able to reach `https://<domain>/api/integrations/gepg/control-number`, `/payment-notification` and
   `/reconciliation` (give GePG these addresses).

The `gov-mocks` project plays GePG for development and UAT rehearsal (`dev-keys/` holds TEST-ONLY keys).

## Backups and restore

The backup container runs `backup.sh` daily (`BACKUP_CRON`) and ships WAL every 5 minutes. To back up now:
`docker compose -f deploy/docker-compose.prod.yml exec backup backup.sh`.

**Quarterly restore test** (record date, file, duration and the counts printed):

```bash
docker compose -f deploy/docker-compose.prod.yml exec backup ls -1t /backups/daily | head
docker compose -f deploy/docker-compose.prod.yml exec backup restore.sh /backups/daily/cims-YYYYMMDD-HHMM.dump.enc
```

It restores into a fresh `cims_restore_test` database and never touches the live one.

**Disaster recovery to the latest dump** (RTO target 4 hours): new server, install as above without starting the backend,
copy the backups back from the second location into the `backups` volume, then
`restore.sh /backups/daily/<latest>.dump.enc cims`, start the backend.

**Point-in-time recovery** (lose at most 15 minutes): stop PostgreSQL; decrypt the latest base backup
(`openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:/run/secrets/cims/backup-passphrase < base-….tar.gz.enc | tar xz -C <empty data dir>`);
decrypt the WAL segments after it into a directory; set
`restore_command = 'cp /restore-wal/%f %p'` and, if needed, `recovery_target_time`; create `recovery.signal`; start PostgreSQL.

## Upgrades

```bash
git pull && docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env up -d --build backend frontend
```

Flyway applies new migrations at start-up. Take a backup first (`exec backup backup.sh`). Announce maintenance outside
07:00–22:00 EAT (NFR-AVL-01).

## Monitoring

- Uptime monitor on `https://<any institution domain>/actuator/health/readiness` (expects `{"status":"UP"}`), every minute.
- The platform console's **Integrations** page shows each outside system's last calls and failures.
- Alert on: readiness down, backup log line `OFFSITE COPY FAILED` or no `backup: done` for 26 hours, disk above 80 %.
