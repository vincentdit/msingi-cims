#!/bin/bash
# Watches the CIMS server and emails an alert when something needs attention (deploy/README.md "Alert on"):
#   the site not answering, a service stopped, the disk above 80 %, no backup in the last 26 hours, the copy to the second
#   location failing (or not set up), the HTTPS certificate expiring within 14 days.
# An alert goes out when a problem appears or clears, and again once a day while it lasts; a weekly report proves the watcher
# itself is alive. Email goes through the same mailbox as CIMS (CIMS_SMTP_* in deploy/.env) to CIMS_ALERT_EMAIL.
#   cims-watch.sh install   run every 10 minutes and the report on Mondays at 07:30 (cron), then a test email
#   cims-watch.sh           check now (what cron runs)
#   cims-watch.sh report    the weekly report: everything checked, fine or not
#   cims-watch.sh test      send a test email
set -uo pipefail
HOME_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$HOME_DIR"
source deploy/scripts/cims-env.sh
STATE=/var/lib/cims-watch; mkdir -p "$STATE"
LOG=/var/log/cims-watch.log
env_get() { grep -E "^$1=" deploy/.env | tail -1 | cut -d= -f2- | sed -E 's/[[:space:]]+#.*$//; s/^[[:space:]]+//; s/[[:space:]]+$//'; }
URL=$(env_get CIMS_WATCH_URL); URL=${URL:-https://app.cims.ac.tz}
TO=$(env_get CIMS_ALERT_EMAIL)
HOSTNAME_=$(hostname)

send() {   # subject, body
  local subject="$1" body="$2"
  echo "$(date '+%F %T') $subject" >> "$LOG"
  local mode host port user pass from ssl scheme
  mode=$(env_get CIMS_EMAIL_MODE); host=$(env_get CIMS_SMTP_HOST); port=$(env_get CIMS_SMTP_PORT); user=$(env_get CIMS_SMTP_USERNAME)
  pass=$(env_get CIMS_SMTP_PASSWORD); from=$(env_get CIMS_EMAIL_FROM); ssl=$(env_get CIMS_SMTP_SSL)
  if [ "$mode" != "smtp" ] || [ -z "$TO" ]; then echo "  (not emailed: CIMS_EMAIL_MODE is not smtp or CIMS_ALERT_EMAIL is empty)" >> "$LOG"; return 0; fi
  scheme=smtp; [ "$ssl" = "true" ] && scheme=smtps
  local msg; msg=$(mktemp)
  { echo "From: CIMS server <$from>"; echo "To: $TO"; echo "Subject: [CIMS] $subject"; echo "Date: $(date -R)"
    echo "Content-Type: text/plain; charset=UTF-8"; echo; echo "$body"; echo; echo "-- cims-watch on $HOSTNAME_ ($URL)"; } > "$msg"
  local rcpts=(); IFS=',' read -ra list <<< "$TO"; for r in "${list[@]}"; do rcpts+=(--mail-rcpt "$(echo "$r" | xargs)"); done
  curl -sS --max-time 30 --url "$scheme://$host:${port:-587}" --ssl-reqd --user "$user:$pass" --mail-from "$from" "${rcpts[@]}" -T "$msg" >> "$LOG" 2>&1 \
    || echo "  email FAILED" >> "$LOG"
  rm -f "$msg"
}

checks() {   # prints one line per problem; with "all", also what is fine
  local all="${1:-}" p
  # the site
  if curl -fsS --max-time 15 "$URL/actuator/health/readiness" >/dev/null 2>&1; then [ -n "$all" ] && echo "ok   site answers ($URL)"
  else echo "FAIL the site does not answer: $URL/actuator/health/readiness"; fi
  # services
  local want running
  want=$(cims config --services 2>/dev/null | sort); running=$(cims ps --status running --services 2>/dev/null | sort)
  for s in $want; do
    if echo "$running" | grep -qx "$s"; then [ -n "$all" ] && echo "ok   service $s running"; else echo "FAIL service $s is not running"; fi
  done
  # disk
  local use; use=$(df --output=pcent / | tail -1 | tr -dc 0-9)
  if [ "$use" -ge 80 ]; then echo "FAIL disk $use % full"; else [ -n "$all" ] && echo "ok   disk $use % used"; fi
  # backups
  local newest
  newest=$(cims exec -T backup sh -c 'find /backups/daily -name "cims-*.dump.enc" -mmin -1560 | sort | tail -1' 2>/dev/null | tr -d '\r')
  if [ -n "$newest" ]; then [ -n "$all" ] && echo "ok   latest backup $(basename "$newest")"; else echo "FAIL no backup in the last 26 hours"; fi
  if [ -z "$(env_get BACKUP_OFFSITE_CMD)" ]; then echo "FAIL backups are only on this server (BACKUP_OFFSITE_CMD empty: run deploy/scripts/offsite-setup.sh)"
  elif cims logs --since 3h backup 2>/dev/null | grep -q "OFFSITE COPY FAILED"; then echo "FAIL copying the backups to the second location failed in the last 3 hours"
  else [ -n "$all" ] && echo "ok   backups copied to the second location"; fi
  # certificate
  local host end days
  host=$(echo "$URL" | sed -E 's#^https?://##; s#/.*##')
  end=$(echo | timeout 15 openssl s_client -connect "$host:443" -servername "$host" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  if [ -z "$end" ]; then echo "FAIL could not read the HTTPS certificate of $host"
  else days=$(( ($(date -d "$end" +%s) - $(date +%s)) / 86400 ))
    if [ "$days" -lt 14 ]; then echo "FAIL the HTTPS certificate of $host expires in $days days (run deploy/scripts/certs.sh check)"
    else [ -n "$all" ] && echo "ok   HTTPS certificate valid for $days more days"; fi
  fi
  # memory, for the report
  [ -n "$all" ] && echo "info memory: $(free -m | awk '/Mem:/ {print $3 " MB used of " $2 " MB"}'); load: $(cut -d' ' -f1-3 /proc/loadavg)"
  return 0
}

case "${1:-run}" in
  install)
    cat > /etc/cron.d/cims-watch <<CRON
# CIMS server watch (deploy/scripts/cims-watch.sh)
*/10 * * * * root bash $HOME_DIR/deploy/scripts/cims-watch.sh >/dev/null 2>&1
30 7 * * 1 root bash $HOME_DIR/deploy/scripts/cims-watch.sh report >/dev/null 2>&1
CRON
    chmod 644 /etc/cron.d/cims-watch
    [ -n "$TO" ] || echo "Set CIMS_ALERT_EMAIL in deploy/.env (one or more addresses, comma-separated): alerts are only logged until then."
    bash "$HOME_DIR/deploy/scripts/cims-watch.sh" test; echo "Installed: checks every 10 minutes, report on Mondays 07:30. Log: $LOG"; bash "$HOME_DIR/deploy/scripts/cims-watch.sh" report-print ;;
  test)
    send "Test from the CIMS server watch" "If you can read this, alerts from the CIMS server reach you."; echo "Test sent to ${TO:-(nobody: CIMS_ALERT_EMAIL is empty)}; see $LOG" ;;
  report-print) checks all ;;
  report)
    out=$(checks all); n=$(echo "$out" | grep -c '^FAIL')
    send "Weekly report: $( [ "$n" -eq 0 ] && echo 'all fine' || echo "$n problem(s)")" "$out" ;;
  run|*)
    now=$(checks | sort); before=$(cat "$STATE/problems" 2>/dev/null || true)
    if [ "$now" != "$before" ]; then
      if [ -n "$now" ]; then send "Problem on the CIMS server" "$now"$'\n\n'"$( [ -n "$before" ] && echo "Before: "$'\n'"$before")"
      else send "Resolved: the CIMS server is fine again" "Cleared:"$'\n'"$before"; fi
      echo "$now" > "$STATE/problems"; date +%s > "$STATE/alerted"
    elif [ -n "$now" ] && [ $(( $(date +%s) - $(cat "$STATE/alerted" 2>/dev/null || echo 0) )) -gt 86000 ]; then
      send "Still a problem on the CIMS server" "$now"; date +%s > "$STATE/alerted"
    fi ;;
esac
