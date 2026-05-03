#!/usr/bin/env bash
# =============================================================================
# start-embedding-mac.sh
# 在 Apple Silicon 宿主机原生运行 Infinity embedding 服务（Metal GPU / MPS）
#
# 用法：
#   ./scripts/start-embedding-mac.sh            # 前台运行
#   ./scripts/start-embedding-mac.sh --daemon   # 后台运行（日志写入 logs/embedding.log）
# =============================================================================
set -euo pipefail

# ── 配置（可通过环境变量覆盖）────────────────────────────────────────────────
MODEL="${EMBEDDING_MODEL:-BAAI/bge-m3}"
PORT="${EMBEDDING_PORT:-7997}"
BATCH_SIZE="${EMBEDDING_BATCH_SIZE:-8}"
HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
READY_TIMEOUT_SECONDS="${EMBEDDING_READY_TIMEOUT:-300}"
VENV_DIR="$(cd "$(dirname "$0")/.." && pwd)/.venv-embedding"
LOG_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
LOG_FILE="$LOG_DIR/embedding.log"
PID_FILE="$LOG_DIR/embedding.pid"

DAEMON=false
if [[ "${1:-}" == "--daemon" ]]; then
  DAEMON=true
fi

# ── 颜色输出 ─────────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
info()  { echo -e "${GREEN}[embedding]${NC} $*"; }
warn()  { echo -e "${YELLOW}[embedding]${NC} $*"; }
error() { echo -e "${RED}[embedding]${NC} $*" >&2; exit 1; }

# ── 前置检查 ─────────────────────────────────────────────────────────────────
[[ "$(uname -m)" == "arm64" ]] || error "此脚本仅适用于 Apple Silicon (arm64)"

pick_python() {
  local candidate version major minor
  for candidate in python3.11 python3.10 python3.12 python3 python; do
    candidate=$(command -v "$candidate" 2>/dev/null || true)
    [[ -n "$candidate" ]] || continue
    version=$("$candidate" -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
    major=${version%%.*}
    minor=${version##*.}
    if (( major > 3 || (major == 3 && minor >= 10) )); then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

PYTHON=$(pick_python || true)
[[ -n "$PYTHON" ]] || error "未找到 Python >= 3.10，请先安装：brew install python@3.11"

PY_VERSION=$("$PYTHON" -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')")
PY_MAJOR=$(echo "$PY_VERSION" | cut -d. -f1)
PY_MINOR=$(echo "$PY_VERSION" | cut -d. -f2)
[[ "$PY_MAJOR" -ge 3 && "$PY_MINOR" -ge 10 ]] \
  || error "需要 Python >= 3.10，当前版本 $PY_VERSION"

# ── 虚拟环境 ─────────────────────────────────────────────────────────────────
if [[ ! -d "$VENV_DIR" ]]; then
  info "创建虚拟环境 $VENV_DIR ..."
  "$PYTHON" -m venv "$VENV_DIR"
fi

# shellcheck source=/dev/null
source "$VENV_DIR/bin/activate"

# ── 安装依赖 ─────────────────────────────────────────────────────────────────
# infinity-emb 0.0.77 仍会 import optimum.bettertransformer。
# 最新 optimum / transformers 组合已移除或禁用了该模块，因此这里显式固定一组
# 已验证兼容的版本范围，避免运行时 ImportError / ModuleNotFoundError。
bootstrap_ok() {
  python - <<'PY' >/dev/null 2>&1
import infinity_emb
from infinity_emb.cli import cli
from optimum.bettertransformer import BetterTransformer
import click
import typer
import fastapi
import uvicorn
major, minor = map(int, click.__version__.split(".")[:2])
raise SystemExit(0 if (major, minor) < (8, 2) else 1)
PY
}

if ! bootstrap_ok; then
  info "安装/修复兼容依赖（torch + infinity server + pinned cli/runtime deps）..."
  python -m pip install --quiet --upgrade pip
  # 安装支持 MPS 的 PyTorch（Apple Silicon 使用官方 wheel 即可启用 MPS）
  python -m pip install --quiet --upgrade torch torchvision --index-url https://download.pytorch.org/whl/cpu
  # 清理旧的 onnx extra，避免残留约束把 optimum 强行拉回 2.x。
  python -m pip uninstall -y optimum-onnx >/dev/null 2>&1 || true
  python -m pip install --quiet --upgrade --force-reinstall \
    "click<8.2" \
    "transformers<4.49" \
    "optimum<2" \
    "infinity-emb[server,torch]==0.0.77"
fi

# 验证 MPS 可用性
MPS_STATUS=$(python - <<'EOF'
import torch
if torch.backends.mps.is_available():
    print("available")
elif torch.backends.mps.is_built():
    print("built_not_available")
else:
    print("unavailable")
EOF
)

if [[ "$MPS_STATUS" == "available" ]]; then
  info "✅ Metal GPU (MPS) 可用，将使用 GPU 加速推理"
  DEVICE="mps"
elif [[ "$MPS_STATUS" == "built_not_available" ]]; then
  warn "⚠️  MPS 已编译但当前不可用（可能缺少 Metal 支持），降级到 CPU"
  DEVICE="cpu"
else
  warn "⚠️  MPS 不可用，降级到 CPU"
  DEVICE="cpu"
fi

# ── 日志目录 ─────────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR"

# ── 启动 Infinity ─────────────────────────────────────────────────────────────
export HF_HOME

info "模型：$MODEL"
info "端口：$PORT"
info "Batch Size：$BATCH_SIZE"
info "HF 缓存：$HF_HOME"
info "Python：$PYTHON ($PY_VERSION)"
info "Ready Timeout：${READY_TIMEOUT_SECONDS}s"
info "后台模式：$DAEMON"

CMD=(
  infinity_emb v2
  --model-id "$MODEL"
  --host 0.0.0.0
  --port "$PORT"
  --url-prefix /v1
  --engine torch
  --dtype auto
  --batch-size "$BATCH_SIZE"
)

if [[ "$DAEMON" == true ]]; then
  info "启动后台进程，日志输出至 $LOG_FILE"
  nohup "${CMD[@]}" >> "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  info "PID：$(cat "$PID_FILE")"
  info "停止命令：kill \$(cat $PID_FILE)"

  # 等待健康检查
  info "等待服务就绪（首次启动会下载模型，可能需要数分钟）..."
  for i in $(seq 1 "$READY_TIMEOUT_SECONDS"); do
    if curl -sf "http://localhost:$PORT/health" &>/dev/null; then
      info "✅ Embedding 服务已就绪：http://localhost:$PORT/v1"
      exit 0
    fi
    sleep 1
    printf "."
  done
  echo ""
  if kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    warn "服务仍在初始化中（通常是首次下载模型）；请继续查看日志：tail -f $LOG_FILE"
  else
    warn "服务启动失败，请检查日志：tail -f $LOG_FILE"
  fi
else
  info "前台启动（Ctrl+C 退出）..."
  exec "${CMD[@]}"
fi
