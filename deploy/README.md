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
# TLS certificate: deploy/certs/fullchain.pem and privkey.pem (one certificate for every host: scripts/certs.sh, below)
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

## Pilot server (small VPS)

The first pilot runs on one small VPS (NetPoa Cloud VPS 1: 2 vCPU, about 2 GB of memory, 38 GB disk, Ubuntu 24.04) at
`/opt/msingi-cims`, with the platform console on `app.cims.ac.tz` and the first college on `bahari.cims.ac.tz`. DNS stays
with the domain's registrar (an A record per host, pointing at the server). Everything below is in this folder.

| What | File or command |
|---|---|
| Memory limits and smaller PostgreSQL settings | `docker-compose.memory.yml` + `postgres/postgresql.small.conf`; `CIMS_DB_POOL=10` |
| The `cims` shortcut (both compose files and `.env`) | `source /opt/msingi-cims/deploy/scripts/cims-env.sh` |
| Swap, firewall, fail2ban, security updates (once) | `scripts/harden-server.sh` |
| Certificates renewed without stopping the proxy | `scripts/certs.sh setup` once, then `scripts/certs.sh add <host>` for each new college |
| Backups proved: backup, restore test, counts, disk | `scripts/check-backups.sh` (quarterly, and after any backup change) |
| Platform address opens the platform sign-in | `nginx/cims.conf` (any host starting with `app.`) |
| NACTVET reachable when the server's DNS answer is not | `NACTE_PREFER_IP=41.93.76.6` in `.env` |
| GePG simulated with no keys installed | leave the four `CIMS_GEPG_*` key settings empty |
| Rehearsing payments | `CIMS_ALLOW_PAYMENT_SIMULATION=true` (off by default in production; CIMS warns at start-up while it is on) |

Updating the server from a Windows PC (only `backend`, `frontend` and `deploy` are sent; `.env`, `secrets` and `certs` stay
on the server):

```powershell
cd C:\dev\msingi-cims
tar.exe -czf "$env:TEMP\cims-update.tar.gz" --exclude=node_modules --exclude=target --exclude=dist --exclude=.env --exclude=secrets --exclude=certs --exclude=acme backend frontend deploy
scp "$env:TEMP\cims-update.tar.gz" root@<server>:/root/
```

```bash
cd /opt/msingi-cims && tar -xzf /root/cims-update.tar.gz && source deploy/scripts/cims-env.sh
cims exec -T backup backup.sh                     # a backup before any upgrade
cims build backend frontend backup && cims up -d
cims logs --tail=60 backend                       # "Production settings checked", then "Started CimsApplication"
```

Before real money: `CIMS_ALLOW_PAYMENT_SIMULATION` removed, payment routes confirmed with the college, SMTP and SMS set,
`BACKUP_OFFSITE_CMD` set and `scripts/check-backups.sh` passed, `scripts/certs.sh check` showing every host.

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
