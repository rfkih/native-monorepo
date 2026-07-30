-- Native dev Postgres bootstrap (M0.4).
--
-- Creates one database + one unprivileged app role per service (DB-per-service,
-- rule 1). Each service connects as its OWN non-superuser role (no BYPASSRLS) so
-- row-level security is genuinely enforced (rule 5) — exactly as the Testcontainers
-- `app_user` pattern does in tests. The role is granted REPLICATION so Debezium can,
-- if desired, connect as it to tail that database's outbox via logical decoding.
--
-- This runs once, on first container boot (docker-entrypoint-initdb.d).

-- ---- org-service ----
CREATE ROLE org_service LOGIN PASSWORD 'org_service' REPLICATION;
CREATE DATABASE org_service OWNER org_service;

-- ---- restaurant-service ----
CREATE ROLE restaurant_service LOGIN PASSWORD 'restaurant_service' REPLICATION;
CREATE DATABASE restaurant_service OWNER restaurant_service;

-- ---- carwash-service (2nd vertical) ----
CREATE ROLE carwash_service LOGIN PASSWORD 'carwash_service' REPLICATION;
CREATE DATABASE carwash_service OWNER carwash_service;

-- ---- barbershop-service (3rd vertical) ----
CREATE ROLE barbershop_service LOGIN PASSWORD 'barbershop_service' REPLICATION;
CREATE DATABASE barbershop_service OWNER barbershop_service;

-- ---- finance-service ----
CREATE ROLE finance_service LOGIN PASSWORD 'finance_service' REPLICATION;
CREATE DATABASE finance_service OWNER finance_service;

-- ---- employee-service ----
CREATE ROLE employee_service LOGIN PASSWORD 'employee_service' REPLICATION;
CREATE DATABASE employee_service OWNER employee_service;

-- ---- notification-service ----
CREATE ROLE notification_service LOGIN PASSWORD 'notification_service' REPLICATION;
CREATE DATABASE notification_service OWNER notification_service;

-- ---- entitlement-service ----
CREATE ROLE entitlement_service LOGIN PASSWORD 'entitlement_service' REPLICATION;
CREATE DATABASE entitlement_service OWNER entitlement_service;

-- Each service owns its schema so Flyway (running as the app role) can create its
-- tables; PostgreSQL 16 revokes CREATE on public from PUBLIC by default.
\connect org_service
GRANT ALL ON SCHEMA public TO org_service;

\connect restaurant_service
GRANT ALL ON SCHEMA public TO restaurant_service;

\connect carwash_service
GRANT ALL ON SCHEMA public TO carwash_service;

\connect barbershop_service
GRANT ALL ON SCHEMA public TO barbershop_service;

\connect finance_service
GRANT ALL ON SCHEMA public TO finance_service;

\connect employee_service
GRANT ALL ON SCHEMA public TO employee_service;

\connect notification_service
GRANT ALL ON SCHEMA public TO notification_service;

\connect entitlement_service
GRANT ALL ON SCHEMA public TO entitlement_service;
