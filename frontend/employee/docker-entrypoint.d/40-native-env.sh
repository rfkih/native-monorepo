#!/bin/sh
# Regenerate the SPA runtime config from the container's env vars BEFORE nginx starts, so a single
# built image serves every environment (window.__NATIVE_CONFIG__ → src/lib/config.ts, shared with
# the console via the `@` alias). Runs via the nginx base image's /docker-entrypoint.d mechanism.
set -eu
TARGET=/usr/share/nginx/html/env.js
cat > "$TARGET" <<EOF
window.__NATIVE_CONFIG__ = {
  authMode: "${NATIVE_AUTH_MODE:-oidc}",
  apiBaseUrl: "${NATIVE_API_BASE_URL:-}",
  keycloakUrl: "${NATIVE_KEYCLOAK_URL:-}",
  keycloakRealm: "${NATIVE_KEYCLOAK_REALM:-native}"
};
EOF
echo "native-employee: wrote runtime config to $TARGET"
