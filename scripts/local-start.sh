#!/usr/bin/env bash
# =============================================================================
# local-start.sh
# 在 Apple Silicon 宿主机上一键启动所有 dawn-ai 服务（无需 Docker）
#
# 用法：
#   ./scripts/local-start.sh                  # 启动全部服务
#   ./scripts/local-start.sh --skip-infra     # 跳过 Redis/PG（已在运行时用）
#   ./scripts/local-start.sh --skip-telemetry # 跳过 Prometheus/Grafana
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
LOG_DIR="$ROOT_DIR/logs"
APP_LOG="$LOG_DIR/app.log"
APP_PID_FILE="$LOG_DIR/app.pid"

# ── 参数解析 ──────────────────────────────────────────────────────────────────
SKIP_INFRA=false
SKIP_TELEMETRY=false
for arg in "${@}"; do
  case "$arg" in
    --skip-infra)     SKIP_INFRA=true ;;
    --skip-telemetry) SKIP_TELEMETRY=true ;;
  esac
done

# ── 颜色输出 ──────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${GREEN}[dawn-ai]${NC} $*"; }
warn()    { echo -e "${YELLOW}[dawn-ai]${NC} $*"; }
error()   { echo -e "${RED}[dawn-ai]${NC} $*" >&2; exit 1; }
section() { echo -e "\n${CYAN}══ $* ══${NC}"; }

# ── 前置依赖检查 ──────────────────────────────────────────────────────────────
section "前置依赖检查"

# Java
JAVA=$(command -v java || true)
[[ -n "$JAVA" ]] || error "未找到 java，请安装：brew install temurin@17"
JAVA_VER=$("$JAVA" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
[[ "$JAVA_VER" -ge 17 ]] || error "需要 Java >= 17，当前版本 $JAVA_VER（pom.xml: java.version=17）"
info "Java $JAVA_VER ✓"

# Maven（优先项目内 mvnw）
if [[ -f "$ROOT_DIR/mvnw" ]]; then
  MVN="$ROOT_DIR/mvnw"
else
  MVN=$(command -v mvn || true)
  [[ -n "$MVN" ]] || error "未找到 mvn，请安装：brew install maven"
fi
info "Maven ✓"

# Python3（embedding 服务用）
PYTHON3=$(command -v python3 || true)
[[ -n "$PYTHON3" ]] || error "未找到 python3，请安装：brew install python@3.12"
info "Python3 ✓"

# brew（管理 PG / Redis）
BREW=$(command -v brew || true)
[[ -n "$BREW" ]] || error "未找到 Homebrew，请访问 https://brew.sh 安装"
info "Homebrew ✓"

mkdir -p "$LOG_DIR"

# ── 1. 基础设施：Redis + PostgreSQL ──────────────────────────────────────────
if [[ "$SKIP_INFRA" == false ]]; then
  section "启动 Redis"
  if redis-cli ping &>/dev/null; then
    info "Redis 已在运行，跳过"
  else
    brew services start redis
    # 等待就绪
    for i in $(seq 1 10); do
      if redis-cli ping &>/dev/null; then
        info "Redis 已就绪 ✓"
        break
      fi
      sleep 1
      [[ "$i" -eq 10 ]] && error "Redis 启动超时"
    done
  fi

  section "启动 PostgreSQL 16"
  # 确保 pg16 在 PATH 中
  PG_BIN="/opt/homebrew/opt/postgresql@16/bin"
  [[ -d "$PG_BIN" ]] || error "未找到 postgresql@16，请安装：brew install postgresql@16"
  export PATH="$PG_BIN:$PATH"

  if pg_isready -q; then
    info "PostgreSQL 已在运行，跳过"
  else
    brew services start postgresql@16
    for i in $(seq 1 15); do
      if pg_isready -q; then
        info "PostgreSQL 已就绪 ✓"
        break
      fi
      sleep 1
      [[ "$i" -eq 15 ]] && error "PostgreSQL 启动超时"
    done
  fi

  # 确保 dawn 用户、数据库和 pgvector 扩展存在
  section "初始化数据库"
  psql postgres -tc "SELECT 1 FROM pg_roles WHERE rolname='dawn'" \
    | grep -q 1 \
    || psql postgres -c "CREATE USER dawn WITH PASSWORD 'dawn123';" \
    && info "用户 dawn 已存在，跳过创建"

  psql postgres -tc "SELECT 1 FROM pg_database WHERE datname='dawn_ai'" \
    | grep -q 1 \
    || psql postgres -c "CREATE DATABASE dawn_ai OWNER dawn;" \
    && info "数据库 dawn_ai 已存在，跳过创建"

  # 检查 pgvector 扩展
  HAS_VECTOR=$(psql -U dawn -d dawn_ai -tAc "SELECT COUNT(*) FROM pg_extension WHERE extname='vector';")
  if [[ "$HAS_VECTOR" -eq 0 ]]; then
    info "安装 pgvector 扩展..."
    psql -U dawn -d dawn_ai -c "CREATE EXTENSION IF NOT EXISTS vector;" \
      || error "pgvector 扩展安装失败。请先编译安装 pgvector：\n  git clone --branch v0.8.0 https://github.com/pgvector/pgvector /tmp/pgvector && cd /tmp/pgvector && make && make install"
  fi
  info "pgvector 扩展已就绪 ✓"
fi

# ── 2. Embedding 服务 ──────────────────────────────────────────────────────
section "启动 Embedding 服务（MPS）"
EMBED_PORT="${EMBEDDING_PORT:-7997}"

if curl -sf "http://localhost:$EMBED_PORT/health" &>/dev/null; then
  info "Embedding 服务已在运行，跳过"
else
  "$SCRIPT_DIR/start-embedding-mac.sh" --daemon
fi

# ── 3. Telemetry（可选）───────────────────────────────────────────────────────
if [[ "$SKIP_TELEMETRY" == false ]]; then
  section "启动 Telemetry（Prometheus + Grafana）"

  if command -v prometheus &>/dev/null; then
    # 修正 prometheus.yml 的 target（本机用 localhost 而非 Docker hostname）
    PROM_CFG="$ROOT_DIR/prometheus.yml"
    if grep -q "app:8080" "$PROM_CFG"; then
      warn "检测到 prometheus.yml 中使用 Docker hostname 'app:8080'，自动替换为 'localhost:8080'"
      sed -i.bak "s|app:8080|localhost:8080|g" "$PROM_CFG"
    fi
    if ! pgrep -f "prometheus --config" &>/dev/null; then
      nohup prometheus --config.file="$PROM_CFG" \
        >> "$LOG_DIR/prometheus.log" 2>&1 &
      echo $! > "$LOG_DIR/prometheus.pid"
      info "Prometheus 已启动（PID: $(cat "$LOG_DIR/prometheus.pid")）→ http://localhost:9090"
    else
      info "Prometheus 已在运行，跳过"
    fi
  else
    warn "未找到 prometheus，跳过（安装：brew install prometheus）"
  fi

  if command -v grafana-server &>/dev/null || brew list grafana &>/dev/null 2>&1; then
    if ! brew services list | grep grafana | grep -q started; then
      brew services start grafana
      info "Grafana 已启动 → http://localhost:3000（admin / admin）"
    else
      info "Grafana 已在运行，跳过"
    fi
  else
    warn "未找到 grafana，跳过（安装：brew install grafana）"
  fi
fi

# ── 4. 必要环境变量检查 ───────────────────────────────────────────────────────
section "环境变量检查"
MISSING=()
[[ -n "${OPENAI_API_KEY:-}" ]] || MISSING+=("OPENAI_API_KEY")
[[ -n "${BASE_URL:-}" ]]       || MISSING+=("BASE_URL")
[[ -n "${CHAT_MODEL:-}" ]]     || MISSING+=("CHAT_MODEL")

if [[ ${#MISSING[@]} -gt 0 ]]; then
  error "缺少必要环境变量：${MISSING[*]}\n请在 .env.local 中配置后 source，或直接 export"
fi

# 设置本机默认值（覆盖 Docker 服务名）
export EMBEDDING_BASE_URL="${EMBEDDING_BASE_URL:-http://localhost:7997}"
export EMBEDDING_MODEL="${EMBEDDING_MODEL:-BAAI/bge-m3}"
export EMBEDDING_API_KEY="${EMBEDDING_API_KEY:-local-embedding-key}"
export EMBEDDING_DIMENSIONS="${EMBEDDING_DIMENSIONS:-1024}"

info "环境变量 ✓"

# ── 5. 构建并启动 Spring Boot App ─────────────────────────────────────────────
section "构建 Spring Boot App"
cd "$ROOT_DIR"
"$MVN" package -DskipTests -q
info "构建完成 ✓"

section "启动 Spring Boot App"
JAR=$(ls "$ROOT_DIR"/target/dawn-ai-*.jar 2>/dev/null | grep -v original | head -1)
[[ -n "$JAR" ]] || error "未找到构建产物，请检查 Maven 构建日志"

# 若已有实例在运行则先停止
if [[ -f "$APP_PID_FILE" ]]; then
  OLD_PID=$(cat "$APP_PID_FILE")
  if kill -0 "$OLD_PID" 2>/dev/null; then
    warn "检测到旧进程 $OLD_PID，先停止..."
    kill "$OLD_PID"
    sleep 3
  fi
  rm -f "$APP_PID_FILE"
fi

nohup java -jar "$JAR" >> "$APP_LOG" 2>&1 &
APP_PID=$!
echo "$APP_PID" > "$APP_PID_FILE"
info "App 启动中（PID: $APP_PID），日志：$APP_LOG"

# 等待健康检查
info "等待 App 就绪..."
for i in $(seq 1 60); do
  if curl -sf "http://localhost:8080/actuator/health" | grep -q '"status":"UP"'; then
    info "✅ dawn-ai 已就绪：http://localhost:8080"
    break
  fi
  sleep 2
  printf "."
  [[ "$i" -eq 60 ]] && { echo ""; warn "App 启动超时，请检查日志：tail -f $APP_LOG"; }
done
echo ""

# ── 启动摘要 ──────────────────────────────────────────────────────────────────
section "服务地址"
info "App:       http://localhost:8080"
info "Embedding: http://localhost:$EMBED_PORT/v1"
[[ "$SKIP_INFRA" == false ]] && info "Redis:     localhost:6379"
[[ "$SKIP_INFRA" == false ]] && info "PG:        localhost:5432/dawn_ai"
[[ "$SKIP_TELEMETRY" == false ]] && command -v prometheus &>/dev/null && info "Prometheus:http://localhost:9090"
[[ "$SKIP_TELEMETRY" == false ]] && info "Grafana:   http://localhost:3000"
info ""
info "停止所有服务：./scripts/local-stop.sh"
