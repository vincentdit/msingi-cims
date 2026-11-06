#!/bin/bash
# HTTPS certificates for CIMS from Let's Encrypt, renewed without stopping the proxy (webroot validation).
#   certs.sh setup                 switch the existing certificate to webroot renewal, install the deploy hook, test renewal
#   certs.sh add <host> [<host>…]  add institution addresses (each must already point at this server in DNS)
#   certs.sh check                 the names, expiry and the certificate the proxy is actually serving
# One certificate (named app.cims.ac.tz) carries every address. The deploy hook copies renewed files into deploy/certs and
# reloads nginx. Needs deploy/acme mounted in the proxy (docker-compose.prod.yml) and port 80 open.
set -euo pipefail
CIMS_HOME="${CIMS_HOME:-/opt/msingi-cims}"
NAME="${CIMS_CERT_NAME:-app.cims.ac.tz}"
ACME="$CIMS_HOME/deploy/acme"
HOOK=/etc/letsencrypt/renewal-hooks/deploy/cims.sh
source "$CIMS_HOME/deploy/scripts/cims-env.sh"

names() { certbot certificates --cert-name "$NAME" 2>/dev/null | sed -n 's/^ *Domains: //p' | tr ' ' '\n' | sed '/^$/d'; }

install_hook() {
  mkdir -p "$(dirname "$HOOK")"
  cat > "$HOOK" <<HOOKEOF
#!/bin/bash
# Installed by deploy/scripts/certs.sh: put a renewed CIMS certificate where the proxy reads it, then reload nginx.
set -e
LIVE="\${RENEWED_LINEAGE:-/etc/letsencrypt/live/$NAME}"
case "\$LIVE" in */$NAME) ;; *) exit 0 ;; esac
install -m 644 "\$LIVE/fullchain.pem" "$CIMS_HOME/deploy/certs/fullchain.pem"
install -m 600 "\$LIVE/privkey.pem" "$CIMS_HOME/deploy/certs/privkey.pem"
source "$CIMS_HOME/deploy/scripts/cims-env.sh"
cims exec -T proxy nginx -t && cims exec -T proxy nginx -s reload
echo "CIMS certificate deployed: \$(openssl x509 -noout -enddate -in "$CIMS_HOME/deploy/certs/fullchain.pem")"
HOOKEOF
  chmod 755 "$HOOK"
}

ensure_webroot() {
  mkdir -p "$ACME/.well-known/acme-challenge"
  echo ok > "$ACME/.well-known/acme-challenge/cims-check"
  if ! cims exec -T proxy test -f /var/www/acme/.well-known/acme-challenge/cims-check; then
    echo "The proxy does not see $ACME. Recreate it with the new compose file first:  cims up -d --no-deps --force-recreate proxy" >&2
    exit 2
  fi
  rm -f "$ACME/.well-known/acme-challenge/cims-check"
}

issue() {   # issue/expand the certificate for the given names by webroot, then deploy it
  local args=()
  for h in "$@"; do args+=(-d "$h"); done
  certbot certonly --webroot -w "$ACME" --cert-name "$NAME" --expand --non-interactive --keep-until-expiring \
    --deploy-hook "$HOOK" "${args[@]}"
  RENEWED_LINEAGE="/etc/letsencrypt/live/$NAME" "$HOOK"
}

case "${1:-}" in
  setup)
    install_hook; ensure_webroot
    mapfile -t current < <(names)
    [ "${#current[@]}" -gt 0 ] || { echo "no certificate named $NAME; first run: certs.sh add app.cims.ac.tz <institution hosts>" >&2; exit 2; }
    echo "== switching $NAME (${current[*]}) to webroot renewal"
    certbot certonly --webroot -w "$ACME" --cert-name "$NAME" --non-interactive --force-renewal \
      --deploy-hook "$HOOK" $(printf -- '-d %s ' "${current[@]}")
    RENEWED_LINEAGE="/etc/letsencrypt/live/$NAME" "$HOOK"
    echo "== renewal test (nothing is changed)"
    certbot renew --cert-name "$NAME" --dry-run
    systemctl list-timers certbot.timer --no-pager | sed -n 1,3p || true
    ;;
  add)
    shift; [ "$#" -gt 0 ] || { echo "usage: certs.sh add <host> [<host>…]" >&2; exit 1; }
    install_hook; ensure_webroot
    me=$(curl -4 -fsS --max-time 10 https://api.ipify.org || hostname -I | awk '{print $1}')
    for h in "$@"; do
      ip=$(getent ahostsv4 "$h" | awk 'NR==1{print $1}')
      [ "$ip" = "$me" ] || { echo "$h points at '${ip:-nothing}', not this server ($me): fix DNS first" >&2; exit 2; }
    done
    mapfile -t current < <(names)
    issue "${current[@]}" "$@"
    ;;
  check)
    certbot certificates --cert-name "$NAME"
    echo "== served by the proxy:"
    for h in $(names); do
      printf '%-28s ' "$h"
      echo | openssl s_client -connect 127.0.0.1:443 -servername "$h" 2>/dev/null | openssl x509 -noout -enddate 2>/dev/null || echo "no answer"
    done
    ;;
  *) sed -n 2,8p "$0"; exit 1 ;;
esac
