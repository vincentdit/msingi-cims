#!/bin/bash
# CIMS database backup (NFR-AVL-02/03).
#   backup.sh        daily: logical dump (pg_dump -Fc) and physical base backup (pg_basebackup), both encrypted; prune; ship
#   backup.sh wal    move archived WAL segments into the encrypted WAL store; ship
#
# Files (all AES-256-CBC with PBKDF2, passphrase from BACKUP_PASSPHRASE_FILE):
#   $BACKUP_DIR/daily/cims-YYYYMMDD-HHMM.dump.enc        kept BACKUP_DAILY_KEEP_DAYS (35)
#   $BACKUP_DIR/daily/base-YYYYMMDD-HHMM.tar.gz.enc      kept BACKUP_DAILY_KEEP_DAYS (35)
#   $BACKUP_DIR/monthly/cims-YYYYMM01-*.dump.enc         the first backup of each month, kept BACKUP_MONTHLY_KEEP_MONTHS (12)
#   $BACKUP_DIR/wal/<segment>.enc                        kept as long as the oldest base backup needs them
set -euo pipefail

DIR="${BACKUP_DIR:-/backups}"
PASS="${BACKUP_PASSPHRASE_FILE:?BACKUP_PASSPHRASE_FILE is required}"
DAYS="${BACKUP_DAILY_KEEP_DAYS:-35}"
MONTHS="${BACKUP_MONTHLY_KEEP_MONTHS:-12}"
WAL_IN="${WAL_ARCHIVE_DIR:-/wal}"
[ -s "$PASS" ] || { echo "backup: passphrase file $PASS is missing or empty" >&2; exit 2; }
mkdir -p "$DIR/daily" "$DIR/monthly" "$DIR/wal"
trap 'rm -f "$DIR"/daily/*.part "$DIR"/daily/.verify.part "$DIR"/wal/*.part' EXIT   # never leave a half-written file that looks like a backup

encrypt() { openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$PASS"; }
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') backup: $*"; }

ship() {
  if [ -n "${BACKUP_OFFSITE_CMD:-}" ]; then
    log "copying to the second location"
    bash -c "$BACKUP_OFFSITE_CMD" || { log "OFFSITE COPY FAILED"; exit 3; }
  fi
}

wal() {
  shopt -s nullglob
  local n=0
  for f in "$WAL_IN"/*; do
    local name; name=$(basename "$f")
    if [ ! -f "$DIR/wal/$name.enc" ]; then
      encrypt < "$f" > "$DIR/wal/$name.enc.part"
      mv "$DIR/wal/$name.enc.part" "$DIR/wal/$name.enc"
    fi
    rm -f "$f"; n=$((n + 1))
  done
  [ "$n" -gt 0 ] && log "$n WAL segment(s) archived"
  [ "$n" -gt 0 ] && ship
  return 0
}

if [ "${1:-}" = "wal" ]; then wal; exit 0; fi

STAMP=$(date +%Y%m%d-%H%M)
DUMP="$DIR/daily/cims-$STAMP.dump.enc"
BASE="$DIR/daily/base-$STAMP.tar.gz.enc"

log "logical dump of ${PGDATABASE:-cims}"
pg_dump -Fc --no-owner "${PGDATABASE:-cims}" | encrypt > "$DUMP.part"
mv "$DUMP.part" "$DUMP"

log "base backup (for point-in-time recovery)"
pg_basebackup -D - -Ft -X fetch -z | encrypt > "$BASE.part"
mv "$BASE.part" "$BASE"

# the first backup of the month is also kept as a monthly one
MONTH=$(date +%Y%m)
if ! ls "$DIR/monthly/cims-${MONTH}"* >/dev/null 2>&1; then
  cp "$DUMP" "$DIR/monthly/cims-${MONTH}01-${STAMP#*-}.dump.enc"
  log "monthly copy kept"
fi

# integrity: each file must decrypt, and the dump must list its contents
CHECK="$DIR/daily/.verify.part"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASS" < "$DUMP" > "$CHECK"
pg_restore --list "$CHECK" >/dev/null
rm -f "$CHECK"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASS" < "$BASE" | gzip -t
log "dump verified ($(du -h "$DUMP" | cut -f1)), base backup $(du -h "$BASE" | cut -f1)"

log "pruning: daily older than $DAYS days, monthly older than $MONTHS months"
find "$DIR/daily" -name '*.enc' -mtime +"$DAYS" -print -delete
find "$DIR/monthly" -name '*.enc' -mtime +"$((MONTHS * 31))" -print -delete
# WAL older than the oldest base backup kept is no longer needed
OLDEST=$(ls -1tr "$DIR"/daily/base-*.enc 2>/dev/null | head -1 || true)
[ -n "$OLDEST" ] && find "$DIR/wal" -name '*.enc' ! -newer "$OLDEST" -print -delete

wal
ship
log "done"
