# Multi-stage build for the console SPA.
# Stage 1 builds the static bundle (tsc + vite); stage 2 serves it with a non-root nginx.

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
# Install deps first (cached across source-only changes).
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Produces dist/ (mode=production → .env.production: real OIDC). Runtime URLs are injected later.
RUN npm run build

# ---- runtime (non-root) ----
# nginx-unprivileged runs as uid 101 and listens on 8080 — no root, no NET_BIND_SERVICE.
FROM nginxinc/nginx-unprivileged:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
# Own the web root as the runtime user so the entrypoint can rewrite env.js at start.
COPY --chown=101:101 --from=build /app/dist /usr/share/nginx/html
# Regenerate window.__NATIVE_CONFIG__ from env vars before nginx starts (base-image .d hook).
COPY --chmod=755 docker-entrypoint.d/40-native-env.sh /docker-entrypoint.d/40-native-env.sh
EXPOSE 8080
