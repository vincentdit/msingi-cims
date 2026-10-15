#!/bin/sh
# Runs once, when the database volume is first created (as the postgres superuser).
# The application role must NOT be a superuser and must NOT have BYPASSRLS: PostgreSQL never applies row-level security
# to such roles, which would switch off tenant isolation between institutions (NFR-SEC-05). CIMS refuses to start otherwise.
set -eu
psql -v ON_ERROR_STOP=1 --username postgres <<SQL
CREATE ROLE cims LOGIN PASSWORD '${CIMS_DB_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE DATABASE cims OWNER cims;
SQL
