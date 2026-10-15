# Encrypted backups of the CIMS database (NFR-AVL-02/03): pg_dump and pg_basebackup on a schedule, encrypted with
# AES-256 (OpenSSL, PBKDF2) before they touch the disk, kept 35 days (daily) and 12 months (monthly), and the WAL archive
# encrypted and shipped for point-in-time recovery.
FROM postgres:16-alpine
RUN apk add --no-cache openssl bash tzdata
ENV TZ=Africa/Dar_es_Salaam
COPY backup.sh restore.sh entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/backup.sh /usr/local/bin/restore.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
