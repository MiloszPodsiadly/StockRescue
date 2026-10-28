FROM ghcr.io/astral-sh/uv:0.13.6@sha256:af22a52f5eafef3d78e551599d405c02df61919d18028f0cea8eda93bc9986d0 AS uv
FROM python:3.15.2-slim@sha256:c4158543ecb725b99e57d129dfd43d761d4c43e4577b5e23326aabb223cf7f4e AS build
COPY --from=uv /uv /bin/uv
WORKDIR /app
COPY intelligence/pyproject.toml intelligence/uv.lock intelligence/.python-version ./
COPY intelligence/src/ src/
RUN --mount=type=cache,target=/root/.cache/uv uv sync --locked --no-dev --no-editable --link-mode=copy

FROM python:3.15.2-slim@sha256:c4158543ecb725b99e57d129dfd43d761d4c43e4577b5e23326aabb223cf7f4e
WORKDIR /app
COPY --from=build --chown=10001:10001 /app/.venv /app/.venv
COPY LICENSE /app/LICENSE
ENV PATH="/app/.venv/bin:$PATH" PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001:10001
EXPOSE 8000
CMD ["stockrescue-intelligence"]
