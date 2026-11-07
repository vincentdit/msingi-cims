#!/bin/bash
# Proves the backups work (NFR-AVL-03): takes a backup now, restores the newest dump into a scratch database and compares
# row counts, then reports the files, their size, the free disk and whether a second location is set.
# Record the date, the dump file, the duration and the counts printed (quarterly restore test, deploy/README.md).
set -euo pipefail
source "${CIMS_HOME:-/opt/msingi-cims}/deploy/scripts/cims-env.sh"

echo "== backup now"
start=$(date +%s)
cims exec -T backup backup.sh
echo "   took $(( $(date +%s) - start )) s"

echo "== newest files"
cims exec -T backup sh -c 'ls -lh /backups/daily | tail -4; echo; du -sh /backups/daily /backups/monthly /backups/wal 2>/dev/null'
latest=$(cims exec -T backup sh -c 'ls -1t /backups/daily/cims-*.dump.enc | head -1' | tr -d '\r')
[ -n "$latest" ] || { echo "no dump found" >&2; exit 2; }

echo "== restore test of $latest (into cims_restore_test; the live database is not touched)"
start=$(date +%s)
cims exec -T backup restore.sh "$latest"
echo "   took $(( $(date +%s) - start )) s"

echo "== row counts, live and restored"
q="select 'institutions', count(*) from tenants union all select 'applications', count(*) from applications
   union all select 'students', count(*) from students union all select 'payments', count(*) from payments"
for db in cims cims_restore_test; do
  echo "-- $db"; cims exec -T db psql -U postgres -d "$db" -Atc "$q" 2>&1 | sed 's/|/: /' || true
done

echo "== disk"
df -h / | tail -1
off=$(grep -E '^BACKUP_OFFSITE_CMD=' "${CIMS_HOME:-/opt/msingi-cims}/deploy/.env" | cut -d= -f2- | sed -E 's/[[:space:]]+#.*$//; s/^[[:space:]]+//')
if [ -z "$off" ]; then echo "!! BACKUP_OFFSITE_CMD is empty: backups exist only on this server"; else echo "second location: $off"; fi
