#!/usr/bin/env bash
# Generate the value of LANGFUSE_AUTH_BASE64 used by the OTLP exporter.
# Reads LANGFUSE_INIT_PROJECT_PUBLIC_KEY / SECRET_KEY from .env (or env).
set -euo pipefail

if [[ -f .env ]]; then
  set -a; source .env; set +a
fi

: "${LANGFUSE_INIT_PROJECT_PUBLIC_KEY:?missing LANGFUSE_INIT_PROJECT_PUBLIC_KEY}"
: "${LANGFUSE_INIT_PROJECT_SECRET_KEY:?missing LANGFUSE_INIT_PROJECT_SECRET_KEY}"

printf '%s:%s' \
  "$LANGFUSE_INIT_PROJECT_PUBLIC_KEY" \
  "$LANGFUSE_INIT_PROJECT_SECRET_KEY" \
  | base64
