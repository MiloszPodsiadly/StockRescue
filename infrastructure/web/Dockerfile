# The upstream 26.13.0 binary is stable; its official Docker tag is not published yet.
FROM python:3.15.2-slim@sha256:c4158543ecb725b99e57d129dfd43d761d4c43e4577b5e23326aabb223cf7f4e AS build
ARG NODE_VERSION=26.13.0
ARG NODE_SHA256_X64=d28a96af6c38596f6d9d97c0ee531c13d238d757742387d1928de822d959e997
ARG NODE_SHA256_ARM64=406fe3fbde1c054adff8b297c16fdb5af6659db327c1ac8acd46b56ef1af9a92
COPY infrastructure/web/install_node.py /tmp/install_node.py
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 libstdc++6
RUN python /tmp/install_node.py
ENV PATH="/opt/node/bin:$PATH"
RUN npm install --global pnpm@12.16.1
WORKDIR /app
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml frontend/.npmrc ./
COPY scripts/verify-npm-exceptions.mjs /tmp/verify-npm-exceptions.mjs
RUN --mount=type=cache,target=/app/.tools/pnpm-store node /tmp/verify-npm-exceptions.mjs /app/pnpm-lock.yaml && pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
COPY infrastructure/web/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
COPY LICENSE /usr/share/nginx/LICENSE
EXPOSE 8080
