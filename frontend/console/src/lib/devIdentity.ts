/**
 * Dev stand-in for the authenticated principal. In production the gateway validates the JWT and
 * injects X-Company-Id / X-Actor; in local dev there is no gateway auth, so the console sends this
 * actor as X-Actor (mirroring the backend DevTenantFilter). Never used as a tenant — only an actor.
 */
export const DEV_ACTOR = 'owner@console.dev'
