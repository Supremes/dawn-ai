#!/usr/bin/env bash
# 生成 OTLP exporter 用的 LANGFUSE_AUTH_BASE64 值。
# 优先从环境变量或 .env 读取 Langfuse Cloud 的 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY，
# 未设置时回退兼容本地自托管的 LANGFUSE_INIT_PROJECT_PUBLIC_KEY / LANGFUSE_INIT_PROJECT_SECRET_KEY。
set -euo pipefail

read_env_key() {
  local key="$1"
  [[ -f .env ]] || return 0
  local line
  line="$(grep -E "^[[:space:]]*${key}=" .env | tail -n 1 || true)"
  [[ -n "$line" ]] || return 0
  local val="${line#*=}"
  # 去除首尾空白及成对单/双引号
  val="${val#"${val%%[![:space:]]*}"}"
  val="${val%"${val##*[![:space:]]}"}"
  if [[ ( "$val" == \"*\" && "$val" == *\" ) || ( "$val" == \'*\' && "$val" == *\' ) ]]; then
    val="${val:1:${#val}-2}"
  fi
  printf '%s' "$val"
}

PUBLIC_KEY="${LANGFUSE_PUBLIC_KEY:-$(read_env_key LANGFUSE_PUBLIC_KEY)}"
PUBLIC_KEY="${PUBLIC_KEY:-${LANGFUSE_INIT_PROJECT_PUBLIC_KEY:-$(read_env_key LANGFUSE_INIT_PROJECT_PUBLIC_KEY)}}"

SECRET_KEY="${LANGFUSE_SECRET_KEY:-$(read_env_key LANGFUSE_SECRET_KEY)}"
SECRET_KEY="${SECRET_KEY:-${LANGFUSE_INIT_PROJECT_SECRET_KEY:-$(read_env_key LANGFUSE_INIT_PROJECT_SECRET_KEY)}}"

: "${PUBLIC_KEY:?missing LANGFUSE_PUBLIC_KEY (or LANGFUSE_INIT_PROJECT_PUBLIC_KEY)}"
: "${SECRET_KEY:?missing LANGFUSE_SECRET_KEY (or LANGFUSE_INIT_PROJECT_SECRET_KEY)}"

printf '%s:%s' "$PUBLIC_KEY" "$SECRET_KEY" | base64 | tr -d '\n'
printf '\n'
