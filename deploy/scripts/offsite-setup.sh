#!/bin/bash
# Sends the encrypted backups to a second location (NFR-AVL-03): a Hetzner Storage Box (or any SFTP account) in another data
# centre. Asks for the account, writes deploy/secrets/rclone.conf (the password obscured, the file readable by root only), sets
# BACKUP_OFFSITE_CMD in deploy/.env, restarts the backup service and proves it with a backup copied there now.
#   offsite-setup.sh          set up (or change) the second location
#   offsite-setup.sh check    list what is at the second location
# The backups are already encrypted with the backup passphrase before they leave the server.
set -euo pipefail
cd "$(dirname "$0")/../.."
source deploy/scripts/cims-env.sh
REMOTE=offsite
DIR=cims-backups

if [ "${1:-}" = "check" ]; then
  cims exec -T backup sh -c "rclone size $REMOTE:$DIR && rclone lsf --max-depth 1 $REMOTE:$DIR/daily | tail -5"
  exit 0
fi

read -p "SFTP host (Storage Box: uNNNNNN.your-storagebox.de): " HOST
read -p "User (Storage Box: uNNNNNN): " USER_
read -p "Port [23 for a Storage Box, 22 otherwise]: " PORT; PORT=${PORT:-23}
read -s -p "Password: " PW; echo
[ -n "$HOST" ] && [ -n "$USER_" ] && [ -n "$PW" ] || { echo "Host, user and password are all needed. Nothing changed."; exit 1; }

OBSCURED=$(cims run --rm --no-deps -T --entrypoint rclone backup obscure "$PW")
mkdir -p deploy/secrets
umask 077
cat > deploy/secrets/rclone.conf <<CONF
[$REMOTE]
type = sftp
host = $HOST
user = $USER_
port = $PORT
pass = $OBSCURED
shell_type = unix
md5sum_command = none
sha1sum_command = none
CONF
chmod 600 deploy/secrets/rclone.conf

echo "Checking the account..."
if ! cims run --rm --no-deps -T --entrypoint rclone backup mkdir "$REMOTE:$DIR"; then
  echo "The account refused the connection: check the host, user, port and password (and that SSH/SFTP is enabled on the Storage Box)."
  exit 1
fi

CMD="rclone sync /backups $REMOTE:$DIR --transfers 2 --retries 3"
cp deploy/.env deploy/.env.bak
grep -v -E '^BACKUP_OFFSITE_CMD=' deploy/.env > /tmp/env.new
echo "BACKUP_OFFSITE_CMD=$CMD" >> /tmp/env.new
cat /tmp/env.new > deploy/.env && rm -f /tmp/env.new
cims up -d backup
echo "Taking a backup now and copying it to the second location (a few minutes)..."
cims exec -T backup backup.sh
cims exec -T backup sh -c "rclone size $REMOTE:$DIR"
echo "Done: every backup and the WAL archive (every 5 minutes) are now copied to $HOST:$DIR."
