#!/usr/bin/env bash
# 生成 OTLP exporter 用的 LANGFUSE_AUTH_BASE64 值。
# 从 .env（或环境变量）读取 LANGFUSE_INIT_PROJECT_PUBLIC_KEY / SECRET_KEY。
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
