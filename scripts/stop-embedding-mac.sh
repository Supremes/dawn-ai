#!/usr/bin/env bash
# =============================================================================
# stop-embedding-mac.sh
# 停止后台运行的 Infinity embedding 服务
# =============================================================================
set -euo pipefail

LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
PID_FILE="$LOG_DIR/embedding.pid"

GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'

if [[ ! -f "$PID_FILE" ]]; then
  echo -e "${RED}[embedding]${NC} 未找到 PID 文件，服务可能未在后台运行"
  exit 1
fi

PID=$(cat "$PID_FILE")
if kill -0 "$PID" 2>/dev/null; then
  kill "$PID"
  rm -f "$PID_FILE"
  echo -e "${GREEN}[embedding]${NC} 已停止进程 $PID"
else
  echo -e "${RED}[embedding]${NC} 进程 $PID 不存在，清理 PID 文件"
  rm -f "$PID_FILE"
fi
