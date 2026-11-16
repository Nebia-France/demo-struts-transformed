FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# Commit sha written into /version.json by scripts/write-version.mjs (the build context has no .git).
ARG GIT_COMMIT_SHA=local
RUN GIT_COMMIT_SHA="$GIT_COMMIT_SHA" npm run build

FROM nginxinc/nginx-unprivileged:stable-alpine
# Precompressed assets are served with `gzip_static` (built into this image). No brotli module is
# installed: the image only configures the Alpine repositories, and neither they nor nginx.org
# publish a brotli module for this nginx build (W9-T02, docs/BENCHMARK.md).
# The OS packages of the base tag are upgraded at build time: the published tag lags behind the
# Alpine security fixes (13 HIGH/CRITICAL in musl, zlib, expat, curl, libxslt, brotli on
# 2026-11-13), and the Trivy step of .github/workflows/frontend-souscription-web.yml blocks on any
# HIGH/CRITICAL (W9-T03, docs/SECURITY_AUDIT.md). The image then runs as the unprivileged uid 101.
USER root
RUN apk upgrade --no-cache
USER 101
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/souscription-web/browser /usr/share/nginx/html
EXPOSE 8080
