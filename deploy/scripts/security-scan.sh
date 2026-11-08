#!/bin/bash
# Security check of the running CIMS (NFR-SEC, monthly and before every go-live): known vulnerabilities in the images and the
# libraries inside them (Trivy), the frontend's npm packages, the TLS and security headers each host answers with, open ports,
# and the sign-in protection settings. Prints a report; with --save also writes it to /var/log/cims/security-<date>.txt.
# Needs Docker; Trivy runs as a container (its database is cached in the volume cims-trivy-cache). Reads, changes nothing.
#   bash deploy/scripts/security-scan.sh            # report on screen
#   bash deploy/scripts/security-scan.sh --save     # and keep a copy (attach it to the pilot's monthly report)
set -uo pipefail
source "${CIMS_HOME:-/opt/msingi-cims}/deploy/scripts/cims-env.sh"
ENV="$CIMS_HOME/deploy/.env"

save=""
[ "${1:-}" = "--save" ] && { mkdir -p /var/log/cims; save="/var/log/cims/security-$(date +%F).txt"; exec > >(tee "$save") 2>&1; }

env_get() { grep -E "^$1=" "$ENV" 2>/dev/null | tail -1 | cut -d= -f2- | sed -E 's/[[:space:]]+#.*$//; s/^"//; s/"$//'; }
high=0

echo "CIMS security check — $(hostname) — $(date '+%F %H:%M %Z')"
echo

echo "== 1. Known vulnerabilities in the running images (HIGH and CRITICAL with a fix available)"
images=$(cims ps --format '{{.Image}}' 2>/dev/null | sort -u)
[ -n "$images" ] || echo "   no running containers found"
for img in $images; do
  out=$(docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v cims-trivy-cache:/root/.cache aquasec/trivy:0.56.2 image \
        --quiet --severity HIGH,CRITICAL --ignore-unfixed --format table "$img" 2>&1)
  n=$(printf '%s\n' "$out" | grep -cE '│ (HIGH|CRITICAL) ' || true)
  high=$((high + n))
  if [ "$n" -eq 0 ]; then echo "   $img: none"; else echo "   $img: $n"; printf '%s\n' "$out" | grep -E '│ (HIGH|CRITICAL) ' | head -20 | sed 's/^/     /'; fi
done
echo

echo "== 2. Frontend packages (npm audit of the production dependencies)"
if [ -f "$CIMS_HOME/frontend/package-lock.json" ]; then
  docker run --rm -v "$CIMS_HOME/frontend:/app:ro" -w /app node:20-alpine sh -c 'npm audit --omit=dev --audit-level=high 2>&1 | tail -6' | sed 's/^/   /'
else
  echo "   frontend/package-lock.json not on the server: run 'npm audit --omit=dev' on the development PC"
fi
echo

echo "== 3. Each host: certificate expiry and security headers"
hosts=$(cims exec -T db psql -U postgres -d cims -Atc "select host from tenant_domains order by host" 2>/dev/null | tr -d '\r')
console=$(env_get CIMS_CONSOLE_HOST); hosts="${console:-app.cims.ac.tz} $hosts"
for h in $hosts; do
  end=$(echo | timeout 8 openssl s_client -servername "$h" -connect "$h:443" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)
  if [ -z "$end" ]; then echo "   $h: no certificate answer"; high=$((high + 1)); continue; fi
  days=$(( ( $(date -d "$end" +%s) - $(date +%s) ) / 86400 ))
  hdr=$(curl -sI --max-time 8 "https://$h/" | tr -d '\r')
  miss=""
  for k in Strict-Transport-Security X-Content-Type-Options X-Frame-Options Referrer-Policy; do
    printf '%s\n' "$hdr" | grep -qi "^$k:" || miss="$miss $k"
  done
  plain=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "http://$h/")
  echo "   $h: certificate ends in $days days; http -> $plain;${miss:+ missing:$miss}${miss:- headers ok}"
  { [ "$days" -lt 14 ] || [ -n "$miss" ]; } && high=$((high + 1))
done
echo

echo "== 4. Ports open to the internet (only 22, 80 and 443 should be)"
ss -tlnH 2>/dev/null | awk '{print $4}' | grep -vE '^(127\.|\[::1\]|::1)' | sed -E 's/.*:([0-9]+)$/\1/' | sort -un | tr '\n' ' ' | sed 's/^/   listening: /'; echo
for p in $(ss -tlnH 2>/dev/null | awk '{print $4}' | grep -vE '^(127\.|\[::1\]|::1)' | sed -E 's/.*:([0-9]+)$/\1/' | sort -un); do
  case "$p" in 22|80|443) ;; *) if command -v ufw >/dev/null && ufw status | grep -qE "^$p(/tcp)? +ALLOW"; then echo "   !! port $p allowed by the firewall"; high=$((high + 1)); fi ;; esac
done
command -v ufw >/dev/null && ufw status | head -1 | sed 's/^/   firewall: /'
echo

echo "== 5. Sign-in protection and secrets"
lm=$(env_get CIMS_LOCK_MINUTES); echo "   lock-out after 5 wrong passwords for ${lm:-15} minutes; locked until unlocked after 15 in a row"
echo "   sign-in requests per address: $(env_get CIMS_SIGN_IN_PER_MINUTE | grep . || echo 60)/minute, $(env_get CIMS_SIGN_IN_PER_HOUR | grep . || echo 1000)/hour"
[ -n "$(env_get CIMS_DATA_KEY)" ] && echo "   data key for authenticator secrets: set" || { echo "   !! CIMS_DATA_KEY is empty"; high=$((high + 1)); }
[ "$(env_get CIMS_ALLOW_PAYMENT_SIMULATION)" = "true" ] && { echo "   !! CIMS_ALLOW_PAYMENT_SIMULATION=true: remove it before real money"; high=$((high + 1)); }
perm=$(stat -c '%a' "$ENV" 2>/dev/null); [ "$perm" = "600" ] && echo "   deploy/.env permissions: 600" || { echo "   !! deploy/.env permissions are $perm (chmod 600)"; high=$((high + 1)); }
echo

if [ "$high" -eq 0 ]; then echo "RESULT: nothing to act on."; else echo "RESULT: $high item(s) to act on (marked above)."; fi
[ -n "$save" ] && echo "Saved to $save"
exit 0
