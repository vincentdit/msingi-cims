#!/bin/bash
# Restores an encrypted CIMS dump into a database (NFR-AVL-03: restoration tested quarterly).
#
#   restore.sh <file.dump.enc> [target-database]
#
# The target defaults to cims_restore_test, created fresh, so a test restore never touches the live database. To replace
# the live database (disaster recovery), stop the backend, then run with target "cims" and confirm.
# Point-in-time recovery from a base backup and the WAL archive is described in deploy/README.md.
set -euo pipefail

FILE="${1:?usage: restore.sh <file.dump.enc> [target-database]}"
TARGET="${2:-cims_restore_test}"
PASS="${BACKUP_PASSPHRASE_FILE:?BACKUP_PASSPHRASE_FILE is required}"
[ -f "$FILE" ] || { echo "restore: no file $FILE" >&2; exit 2; }

if [ "$TARGET" = "cims" ]; then
  read -r -p "This REPLACES the live CIMS database. Type the word RESTORE to continue: " ok
  [ "$ok" = "RESTORE" ] || { echo "restore: cancelled"; exit 1; }
fi

echo "restore: decrypting $(basename "$FILE") into database $TARGET"
psql -v ON_ERROR_STOP=1 -d postgres -c "DROP DATABASE IF EXISTS \"$TARGET\" WITH (FORCE)" -c "CREATE DATABASE \"$TARGET\" OWNER cims"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$PASS" < "$FILE" \
  | pg_restore --no-owner --role=cims --exit-on-error -d "$TARGET"

# a quick look that the data is there
psql -d "$TARGET" -At -c "select 'institutions: ' || count(*) from tenants" -c "select 'students: ' || count(*) from students" \
  -c "select 'payments: ' || count(*) from payments" -c "select 'latest audit entry: ' || coalesce(max(occurred_at)::text, '-') from audit_logs" 2>/dev/null || true
echo "restore: done. Record the test in the restore log (date, file, duration, checks)."
