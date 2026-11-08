# Encrypted backups of the CIMS database (NFR-AVL-02/03): pg_dump and pg_basebackup on a schedule, encrypted with
# AES-256 (OpenSSL, PBKDF2) before they touch the disk, kept 35 days (daily) and 12 months (monthly), and the WAL archive
# encrypted and shipped for point-in-time recovery.
FROM postgres:16-alpine
# apk upgrade: the base image's packages to their latest security fixes at every build (rsync CVE-2026-119741)
RUN apk upgrade --no-cache && apk add --no-cache openssl bash tzdata rclone openssh-client rsync
# rclone reads its remotes from deploy/secrets/rclone.conf when there is one
ENV RCLONE_CONFIG=/run/secrets/cims/rclone.conf
ENV TZ=Africa/Dar_es_Salaam
COPY backup.sh restore.sh entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
