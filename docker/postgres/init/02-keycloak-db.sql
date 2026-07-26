-- LOCAL, uncommitted (pairs with docker/compose.local-override.yml): Keycloak's persistent
-- store. Runs only when the Postgres volume is initialized from scratch; on the existing
-- volume the same role/DB were created manually (2026-07-25).
CREATE ROLE keycloak LOGIN PASSWORD 'keycloak';
CREATE DATABASE keycloak OWNER keycloak;
