# Multi-stage build for the Employee SPA (ADR 0049 P5).
#
# Unlike the console (which builds from its own dir), this package SHARES source with the console
# via a `@` -> ../console/src alias (vite.config.ts + tsconfig paths), so the build CONTEXT must be
# the `frontend/` PARENT — both `employee/` and `console/src` must be present at build time. Build:
#
#   docker build -f frontend/employee/Dockerfile -t native-employee frontend
#
# Only console SOURCE is needed, never console/node_modules: vite `resolve.dedupe` + the employee
# tsconfig `paths` pin every shared runtime dep (react, react-router-dom, @tanstack/react-query, …)
# to THIS package's own node_modules, so the aliased ../console/src modules load a single copy.

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app/employee
# Install deps first (cached across source-only changes).
COPY employee/package.json employee/package-lock.json ./
RUN npm ci
# The shared ../console/src files import bare deps (clsx, lucide-react, …) that node resolution
# looks for by walking UP from the importing file to /app/console/node_modules — which does not
# exist. One symlink points it at THIS package's single node_modules, so console/src resolves every
# dependency (react included, as the SAME instance — no dual-package hazard) from the one install.
RUN mkdir -p /app/console && ln -s /app/employee/node_modules /app/console/node_modules
# The shared console source the `@` alias + tsconfig resolve into (../console/src relative to
# /app/employee == /app/console/src). SOURCE only — console's node_modules is the symlink above.
COPY console/src /app/console/src
# This package's own build inputs (explicit, so the local node_modules/dist are never copied in).
COPY employee/index.html employee/vite.config.ts ./
COPY employee/tsconfig.json employee/tsconfig.app.json employee/tsconfig.node.json ./
COPY employee/public ./public
COPY employee/src ./src
# Produces dist/ via vite (esbuild) — resolves `@` -> ../console/src and pins shared deps to THIS
# package's node_modules through the vite alias + resolve.dedupe, so console/node_modules is not
# needed. We deliberately run `vite build` here rather than the package's `tsc -b && vite build`
# script: the image needs only the BUNDLE, and full `tsc` type-checking of the shared ../console/src
# tree would drag in console's own node_modules just for type resolution. Type-checking stays the
# dev/CI gate (npm run build). Runtime URLs (Keycloak/API) are injected at container start, not baked.
RUN npx vite build

# ---- runtime (non-root) ----
# nginx-unprivileged runs as uid 101 and listens on 8080 — no root, no NET_BIND_SERVICE.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY employee/nginx.conf /etc/nginx/conf.d/default.conf
# Own the web root as the runtime user so the entrypoint can rewrite env.js at start.
COPY --chown=101:101 --from=build /app/employee/dist /usr/share/nginx/html
# Regenerate window.__NATIVE_CONFIG__ from env vars before nginx starts (base-image .d hook).
COPY --chmod=755 employee/docker-entrypoint.d/40-native-env.sh /docker-entrypoint.d/40-native-env.sh
EXPOSE 8080
