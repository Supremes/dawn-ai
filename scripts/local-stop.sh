#!/usr/bin/env bash
# =============================================================================
# local-stop.sh
# 停止所有本机运行的 dawn-ai 服务
#
# 用法：
#   ./scripts/local-stop.sh                # 停止全部（保留 Redis/PG）
#   ./scripts/local-stop.sh --all          # 停止全部，包括 Redis/PG
#   ./scripts/local-stop.sh --keep-telemetry # 保留 Prometheus/Grafana
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
APP_PID_FILE="$LOG_DIR/app.pid"

# ── 参数解析 ──────────────────────────────────────────────────────────────────
STOP_INFRA=false
KEEP_TELEMETRY=false
for arg in "${@}"; do
  case "$arg" in
    --all)            STOP_INFRA=true ;;
    --keep-telemetry) KEEP_TELEMETRY=true ;;
  esac
done

# ── 颜色输出 ──────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${GREEN}[dawn-ai]${NC} $*"; }
warn()    { echo -e "${YELLOW}[dawn-ai]${NC} $*"; }
section() { echo -e "\n${CYAN}══ $* ══${NC}"; }

# ── 1. 停止 Spring Boot App ───────────────────────────────────────────────────
section "停止 Spring Boot App"
if [[ -f "$APP_PID_FILE" ]]; then
  APP_PID=$(cat "$APP_PID_FILE")
  if kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID"
    # 等待优雅退出（最多 10 秒）
    for i in $(seq 1 10); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    # 强制终止（如果还在运行）
    kill -0 "$APP_PID" 2>/dev/null && kill -9 "$APP_PID" && warn "强制终止 App 进程 $APP_PID"
    info "Spring Boot App 已停止（PID: $APP_PID）"
  else
    warn "进程 $APP_PID 不存在，清理 PID 文件"
  fi
  rm -f "$APP_PID_FILE"
else
  warn "未找到 App PID 文件，可能未通过本脚本启动"
  # 尝试通过进程名找到并终止
  pkill -f "dawn-ai.*\.jar" 2>/dev/null && info "通过进程名终止了 App" || true
fi

# ── 2. 停止 Embedding 服务 ────────────────────────────────────────────────────
section "停止 Embedding 服务"
if [[ -f "$SCRIPT_DIR/stop-embedding-mac.sh" ]]; then
  "$SCRIPT_DIR/stop-embedding-mac.sh" || true
else
  pkill -f "infinity_emb" 2>/dev/null && info "Embedding 服务已停止" || warn "Embedding 服务未在运行"
fi

# ── 3. 停止 Prometheus ────────────────────────────────────────────────────────
if [[ "$KEEP_TELEMETRY" == false ]]; then
  section "停止 Prometheus"
  PROM_PID_FILE="$LOG_DIR/prometheus.pid"
  if [[ -f "$PROM_PID_FILE" ]]; then
    PROM_PID=$(cat "$PROM_PID_FILE")
    if kill -0 "$PROM_PID" 2>/dev/null; then
      kill "$PROM_PID"
      info "Prometheus 已停止（PID: $PROM_PID）"
    else
      warn "Prometheus 进程 $PROM_PID 不存在"
    fi
    rm -f "$PROM_PID_FILE"
  else
    pkill -f "prometheus --config" 2>/dev/null && info "Prometheus 已停止" || warn "Prometheus 未在运行"
  fi

  section "停止 Grafana"
  brew services stop grafana 2>/dev/null && info "Grafana 已停止" || warn "Grafana 未在运行或未通过 brew services 启动"
fi

# ── 4. 停止基础设施（仅 --all 时）──────────────────────────────────────────────
if [[ "$STOP_INFRA" == true ]]; then
  section "停止 Redis"
  brew services stop redis && info "Redis 已停止" || warn "Redis 停止失败或未在运行"

  section "停止 PostgreSQL 16"
  brew services stop postgresql@16 && info "PostgreSQL 已停止" || warn "PostgreSQL 停止失败或未在运行"
else
  info "Redis 和 PostgreSQL 保持运行（使用 --all 参数可一并停止）"
fi

section "完成"
info "所有 dawn-ai 服务已停止"
