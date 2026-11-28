#!/usr/bin/env bash
# 建立本地开发机与远程 VPS 指标监控栈（Prometheus + Grafana）之间的 SSH 双向隧道。
#
# 作用：
#   1. 反向转发：VPS 127.0.0.1:${VPS_APP_PORT:-18080} -> 本地 127.0.0.1:${LOCAL_APP_PORT:-8080}
#      让 VPS 上的 Prometheus 抓取本地 dawn-ai 的 /actuator/prometheus
#   2. 正向转发：本地 127.0.0.1:${LOCAL_GRAFANA_PORT:-3000} -> VPS 127.0.0.1:3000 (Grafana)
#   3. 正向转发：本地 127.0.0.1:${LOCAL_PROM_PORT:-9090}    -> VPS 127.0.0.1:9090 (Prometheus)
#
# 用法：
#   ./scripts/vps-metrics-tunnel.sh              # 前台运行（Ctrl+C 断开）
#   VPS_HOST=vps LOCAL_APP_PORT=8080 ./scripts/vps-metrics-tunnel.sh
set -euo pipefail

VPS_HOST="${VPS_HOST:-vps}"
LOCAL_APP_PORT="${LOCAL_APP_PORT:-8080}"
VPS_APP_PORT="${VPS_APP_PORT:-18080}"
LOCAL_GRAFANA_PORT="${LOCAL_GRAFANA_PORT:-3000}"
LOCAL_PROM_PORT="${LOCAL_PROM_PORT:-9090}"

echo "正在建立 SSH 监控隧道 -> ${VPS_HOST}"
echo "  - Metrics 抓取: VPS 127.0.0.1:${VPS_APP_PORT} -> 本地 127.0.0.1:${LOCAL_APP_PORT}"
echo "  - Grafana UI : http://localhost:${LOCAL_GRAFANA_PORT}"
echo "  - Prometheus : http://localhost:${LOCAL_PROM_PORT}"
echo "按 Ctrl+C 停止隧道。"

exec ssh -N \
  -o BatchMode=yes \
  -o ConnectTimeout=10 \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -R "127.0.0.1:${VPS_APP_PORT}:127.0.0.1:${LOCAL_APP_PORT}" \
  -L "127.0.0.1:${LOCAL_GRAFANA_PORT}:127.0.0.1:3000" \
  -L "127.0.0.1:${LOCAL_PROM_PORT}:127.0.0.1:9090" \
  "${VPS_HOST}"
