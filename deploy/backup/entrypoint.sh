#!/bin/bash
# Schedules backup.sh with busybox crond (BACKUP_CRON, default 01:15 daily) and ships the WAL archive every 5 minutes.
set -euo pipefail
: "${BACKUP_CRON:=15 1 * * *}"
env | grep -E '^(PG|BACKUP_|TZ)' | sed 's/^/export /; s/=\(.*\)$/="\1"/' > /etc/backup.env
cat > /etc/crontabs/root <<CRON
${BACKUP_CRON} . /etc/backup.env && /usr/local/bin/backup.sh >> /proc/1/fd/1 2>&1
*/5 * * * * . /etc/backup.env && /usr/local/bin/backup.sh wal >> /proc/1/fd/1 2>&1
CRON
echo "CIMS backup: schedule '${BACKUP_CRON}', WAL shipped every 5 minutes, files in ${BACKUP_DIR:-/backups}"
if [ "${1:-}" = "now" ]; then exec /usr/local/bin/backup.sh; fi
exec crond -f -l 8
