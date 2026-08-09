// Runtime configuration placeholder.
//
// In production this file is REGENERATED at container start by the deploy image's entrypoint from
// the container's env vars, so a single built image works across environments (mirrors the
// console's docker-entrypoint.d/40-native-env.sh — see frontend/console/public/env.js). In local
// dev it stays empty and the app falls back to import.meta.env / .env.* (see the shared
// @/lib/config.ts).
window.__NATIVE_CONFIG__ = window.__NATIVE_CONFIG__ || {}
