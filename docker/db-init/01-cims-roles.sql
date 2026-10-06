-- Runs once, when the Docker volume is first created (as the postgres superuser).
-- The application role must NOT be a superuser and must NOT have BYPASSRLS:
-- PostgreSQL never applies row-level security to such roles, which would
-- switch off tenant isolation between institutions.
CREATE ROLE cims LOGIN PASSWORD 'cims' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE DATABASE cims OWNER cims;
CREATE DATABASE cims_test OWNER cims;   -- integration tests
