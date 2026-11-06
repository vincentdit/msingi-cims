# CIMS on the server: load with   source /opt/msingi-cims/deploy/scripts/cims-env.sh
# Defines "cims", which runs docker compose with the production files, the small-server limits (when present) and .env:
#   cims ps · cims logs --tail=80 backend · cims up -d --no-deps --force-recreate backend · cims exec -T proxy nginx -s reload
CIMS_HOME="${CIMS_HOME:-/opt/msingi-cims}"
cims() {
  local files=(-f "$CIMS_HOME/deploy/docker-compose.prod.yml")
  [ -f "$CIMS_HOME/deploy/docker-compose.memory.yml" ] && [ "${CIMS_FULL_SIZE:-}" != "1" ] && files+=(-f "$CIMS_HOME/deploy/docker-compose.memory.yml")
  docker compose "${files[@]}" --env-file "$CIMS_HOME/deploy/.env" "$@"
}
