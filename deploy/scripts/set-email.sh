#!/bin/bash
# Switches CIMS email to an SMTP mailbox (e.g. Duhosting). Asks for the server and password; changes nothing unless confirmed.
set -e
cd "$(dirname "$0")/../.."
read -p "Mail server name: " H
read -p "Mailbox (login and sender, exactly as in cPanel): " U
[ -n "$U" ] || { echo "Give the full mailbox, e.g. no-reply@cimsac.tz."; exit 1; }
read -s -p "Mailbox password: " P; echo
case "$P" in *'$'*|*'#'*|*' '*) echo "The password contains \$, # or a space: change it in cPanel first."; exit 1;; esac
echo "Certificate names of $H (port 587):"
NAMES=$(echo QUIT | timeout 15 openssl s_client -starttls smtp -connect "$H:587" -servername "$H" 2>/dev/null | openssl x509 -noout -ext subjectAltName 2>/dev/null || true)
if [ -z "$NAMES" ]; then echo "  none: port 587 is closed or the name is wrong. Nothing changed."; exit 1; fi
echo "$NAMES"
echo "$NAMES" | grep -q "DNS:$H\(,\|$\)" || { echo "$H is not one of these names, so CIMS would refuse the connection. Nothing changed: run again with one of the names listed."; exit 1; }
echo "Checking the password with the mail server..."
if curl -sS --max-time 20 --url "smtp://$H:587" --ssl-reqd --user "$U:$P" -X NOOP >/dev/null 2>/tmp/smtp.err; then echo "  signed in."; else echo "  refused: $(cat /tmp/smtp.err)"; echo "Nothing changed."; exit 1; fi
cp deploy/.env deploy/.env.bak
grep -v -E '^CIMS_(EMAIL_MODE|EMAIL_FROM|SMTP_[A-Z]+)=' deploy/.env > /tmp/env.new
printf '%s\n' "CIMS_EMAIL_MODE=smtp" "CIMS_EMAIL_FROM=$U" "CIMS_SMTP_HOST=$H" "CIMS_SMTP_PORT=587" "CIMS_SMTP_AUTH=true" \
  "CIMS_SMTP_STARTTLS=true" "CIMS_SMTP_SSL=false" "CIMS_SMTP_USERNAME=$U" "CIMS_SMTP_PASSWORD=$P" >> /tmp/env.new
cat /tmp/env.new > deploy/.env && rm -f /tmp/env.new /tmp/smtp.err
source deploy/scripts/cims-env.sh
cims up -d backend
echo "Saved (old settings in deploy/.env.bak). Backend restarting: send the test email from the console in a minute."
