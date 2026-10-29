FROM node:26.13.0-bookworm-slim@sha256:bc0a2afd3b74ce12222fc8bab99271e66257f021078f5f4a5d687a77d280c1d9 AS build
RUN npm install --global pnpm@12.16.1
WORKDIR /app
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml frontend/.npmrc ./
RUN --mount=type=cache,target=/app/.tools/pnpm-store pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
ENV BACKEND_HOST=backend BACKEND_PORT=8080 BACKEND_RESOLVER=127.0.0.11 NGINX_ENVSUBST_FILTER="^BACKEND_(HOST|PORT|RESOLVER)$"
COPY infrastructure/web/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
COPY LICENSE /usr/share/nginx/LICENSE
EXPOSE 8080
