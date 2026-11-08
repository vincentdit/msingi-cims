# The reverse proxy: stable nginx with Alpine's latest security fixes (openssl, brotli, c-ares) applied at every build.
# Its configuration, certificates and ACME webroot are mounted by docker-compose.prod.yml.
FROM nginx:1.28-alpine
RUN apk upgrade --no-cache
