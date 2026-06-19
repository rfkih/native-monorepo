/**
 * Single source of runtime configuration for the console.
 *
 * Values resolve in this order: a runtime-injected `window.__NATIVE_CONFIG__` (written by the
 * container's `env.js` at startup, so ONE built image works across environments) → Vite build-time
 * `import.meta.env.VITE_*` → a safe local-dev default. This lets the production Docker image stay
 * config-free until deploy, while local `npm run dev` is driven by `.env.*`.
 */

export type AuthMode = 'oidc' | 'dev'

interface RuntimeConfig {
  authMode?: AuthMode
  apiBaseUrl?: string
  keycloakUrl?: string
  keycloakRealm?: string
}

declare global {
  interface Window {
    __NATIVE_CONFIG__?: RuntimeConfig
  }
}

const rt: RuntimeConfig =
  typeof window !== 'undefined' && window.__NATIVE_CONFIG__ ? window.__NATIVE_CONFIG__ : {}

/**
 * `dev` keeps the header-trust local path (no Keycloak needed); `oidc` runs the real
 * authorization-code + PKCE login against Keycloak and sends a bearer token. Defaults to `dev` so a
 * bare `npm run dev` works offline; the production build sets `oidc`.
 */
export const AUTH_MODE: AuthMode = rt.authMode ?? (import.meta.env.VITE_AUTH_MODE as AuthMode) ?? 'dev'

/** Origin the API calls target. Empty string = same origin (use the Vite dev proxy / the ingress). */
export const API_BASE_URL: string = rt.apiBaseUrl ?? import.meta.env.VITE_API_BASE_URL ?? ''

/** Keycloak base URL, e.g. `http://localhost:18080`. Realm path is appended in the auth layer. */
export const KEYCLOAK_URL: string =
  rt.keycloakUrl ?? import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:18080'

export const KEYCLOAK_REALM: string =
  rt.keycloakRealm ?? import.meta.env.VITE_KEYCLOAK_REALM ?? 'native'

/** The public SPA client id registered in the Keycloak realm (authorization-code + PKCE, no secret). */
export const KEYCLOAK_CLIENT_ID = 'native-console'
