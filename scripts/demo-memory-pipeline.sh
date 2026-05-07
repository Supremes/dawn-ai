#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# Memory 管道 Demo 脚本
#
# 演示 L1→L2→L3→L4 全链路的逐层激活过程:
#   L1  Redis 活跃窗口(容量 20)
#   L2  Pending 溢出触发摘要(batch-size = 3)
#   L3  MemoryConsolidator 写入 VectorStore
#   L4  第 3 次 consolidation 触发 Reflection → 用户画像
#
# 消息数学:
#   每次 POST /api/v1/chat = 1 条 user + 1 条 assistant = 2 条进 L1
#   10 次 chat → L1 = 20(满,不溢出)
#   此后每次 chat 各溢出 2 条旧消息进 Pending
#   2 次 chat = 4 条溢出 → Pending 累计 ≥ 3 → 触发一次摘要
#
# 前提:
#   1. 服务已启动,Redis 和 PGVector 已就绪
#   2. LLM API Key 已配置(每轮对话、L2 摘要、L4 Reflection 均调用 LLM)
#   3. 诊断端点 /api/v1/debug/memory 可访问(状态查询用)
#
# 用法:
#   bash scripts/demo-memory-pipeline.sh
#   BASE_URL=http://your-host:8080 bash scripts/demo-memory-pipeline.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
SESSION_ID="demo-$(date +%s)"

# ── 颜色 ─────────────────────────────────────────────────────────────────────
BOLD='\033[1m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
DIM='\033[2m'
NC='\033[0m'

# ── 工具函数 ──────────────────────────────────────────────────────────────────

pause() {
  echo -e "\n${BOLD}  按 Enter 继续下一阶段...${NC}"
  read -r
}

banner() {
  echo -e "${BOLD}${CYAN}╔══════════════════════════════════════════════════════╗${NC}"
  printf "${BOLD}${CYAN}║  %-52s  ║${NC}\n" "$1"
  echo -e "${BOLD}${CYAN}╚══════════════════════════════════════════════════════╝${NC}"
}

step() {
  echo -e "\n${YELLOW}▶ $1${NC}"
}

ok() {
  echo -e "${GREEN}✓ $1${NC}"
}

err() {
  echo -e "${RED}✗ $1${NC}"
}

# 发送真实对话请求,打印用户消息和 AI 回复
# 用法:send_chat "消息内容"
send_chat() {
  local message="$1"
  local payload
  payload=$(printf '{"message": %s, "sessionId": %s}' \
    "$(printf '%s' "$message" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')" \
    "$(printf '%s' "$SESSION_ID" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')")

  echo -e "${DIM}  › user: $message${NC}"

  http_body=$(curl -sf -X POST "$BASE_URL/api/v1/chat" \
    -H "Content-Type: application/json" \
    --max-time 60 \
    -d "$payload") || {
    err "chat 请求失败(超时或 HTTP 错误),跳过本条消息"
    return 0
  }

  if command -v jq &>/dev/null; then
    answer=$(echo "$http_body" | jq -r '.answer // "(无回复)"')
    duration=$(echo "$http_body" | jq -r '.durationMs // 0')
    echo -e "${DIM}  ‹ assistant: $answer  [${duration}ms]${NC}"
  else
    echo -e "${DIM}  ‹ $http_body${NC}"
  fi
}

# 查询并打印四层状态(使用 debug 诊断端点,仅读不写)
show_state() {
  local label="$1"
  echo -e "\n${BOLD}  [状态快照] $label${NC}"
  response=$(curl -sf "$BASE_URL/api/v1/debug/memory/$SESSION_ID/state")

  if command -v jq &>/dev/null; then
    l1=$(echo "$response" | jq -r '.l1Window')
    l2=$(echo "$response" | jq -r '.l2Pending')
    l3=$(echo "$response" | jq -r '.l3Count')
    l4=$(echo "$response" | jq -r '.l4Profile | keys | length')

    echo -e "  L1 活跃窗口   : ${BOLD}$l1${NC} 条消息"
    echo -e "  L2 Pending    : ${BOLD}$l2${NC} 条消息"
    echo -e "  L3 VectorStore: ${BOLD}$l3${NC} 篇摘要文档"
    echo -e "  L4 用户画像   : ${BOLD}$l4${NC} 个属性"

    l4_content=$(echo "$response" | jq -r '.l4Profile')
    if [ "$l4_content" != "{}" ]; then
      echo -e "  L4 画像内容   :"
      echo "$l4_content" | jq -r 'to_entries[] | "    \(.key): \(.value)"'
    fi
  else
    echo "$response" | python3 -m json.tool 2>/dev/null | sed 's/^/  /' || echo "  $response"
  fi
}

# 健康检查:服务可达 + 诊断端点可达
check_health() {
  step "检查服务连通性"

  # 用 /actuator/health 做连通探针,不调 LLM,秒级返回
  local health_code=000
  health_code=$(curl -o /dev/null -s -w "%{http_code}" --max-time 5 \
    "$BASE_URL/actuator/health") || health_code=000

  if [ "$health_code" = "000" ]; then
    err "无法连接到 $BASE_URL,请确认服务已启动"
    exit 1
  fi
  ok "服务可达 (HTTP $health_code)"

  # 验证诊断端点已激活
  local debug_code=000
  debug_code=$(curl -o /dev/null -s -w "%{http_code}" --max-time 5 \
    "$BASE_URL/api/v1/debug/memory/healthcheck/state") || debug_code=000

  if [ "$debug_code" = "000" ] || [ "$debug_code" = "404" ]; then
    err "诊断端点不可达 (HTTP $debug_code) -- /api/v1/debug/memory 未激活"
    exit 1
  fi
  ok "诊断 API 可达 (HTTP $debug_code)"
}

# ── 主流程 ────────────────────────────────────────────────────────────────────

banner "Memory 管道 Demo — 四层架构激活演示"
echo ""
echo -e "  Session ID : ${GREEN}$SESSION_ID${NC}"
echo -e "  Base URL   : ${GREEN}$BASE_URL${NC}"
echo ""
echo -e "  架构参数:"
echo -e "    L1 活跃窗口容量      = 20 条消息"
echo -e "    L2 Pending 触发阈值  = 3 条  → 调用 LLM 生成摘要"
echo -e "    L3 Consolidation 阈值 = 3 次 → 触发 Reflection"
echo -e "    L4 Reflection        → 提炼用户画像写入 Redis Hash"
echo ""
echo -e "  每次 chat 请求 = user 消息 + AI 回复 = 2 条进 L1"

check_health

# ────────────────────────────────────────────────────────────────────
# 阶段 1:10 轮对话填满 L1 活跃窗口(10×2 = 20 条,不溢出)
# ────────────────────────────────────────────────────────────────────
banner "阶段 1 / 4 — 填满 L1 活跃窗口(10 轮对话)"
echo -e "  10 次 chat × 2 条 = 20 条消息"
echo -e "  预期:L1=20,L2=0,L3=0,L4 为空"

step "发送 10 轮 Go 语言技术对话"
send_chat "Go 语言的 goroutine 和 channel 有什么核心优势?"
send_chat "Go 的 GC 暂停时间如何控制在毫秒级以内?"
send_chat "sync.Map 和 map+RWMutex 在高并发下的性能差异是什么?"
send_chat "Go 的 interface{} 和泛型各自适用于哪些场景?"
send_chat "如何用 pprof 定位 Go 程序的内存泄漏?"
send_chat "Go 模块代理和 vendor 模式的最佳实践是什么?"
send_chat "context.WithTimeout 在微服务链路中如何正确传播?"
send_chat "Go 的 HTTP/2 和 gRPC 在内网通信上有什么取舍?"
send_chat "errgroup 和 WaitGroup 在并发错误处理上有何区别?"
send_chat "Go 编译时的逃逸分析如何影响堆分配性能?"

show_state "阶段 1 完成 — L1 应已满载"
pause

# ────────────────────────────────────────────────────────────────────
# 阶段 2:2 轮对话触发第 1 次摘要(4 条溢出 → Pending ≥ 3 → L2→L3)
# ────────────────────────────────────────────────────────────────────
banner "阶段 2 / 4 — 触发第 1 次摘要(L2→L3)"
echo -e "  L1 已满,每条新消息弹出 1 条旧消息进 Pending"
echo -e "  2 次 chat = 4 条溢出 → Pending 达到 3 → 触发 MemorySummarizer → VectorStore 写入"
echo -e "  预期:L3 count ≥ 1"

step "发送 2 轮性能优化对话(触发第 1 批摘要)"
send_chat "如何用 Go 实现零拷贝的高性能网络 I/O?"
send_chat "Go 服务的 CPU 火焰图中出现大量 runtime.mallocgc 意味着什么?"

step "等待异步摘要管道完成..."
sleep 4

show_state "阶段 2 完成 — L3 应出现第 1 篇摘要"
pause

# ────────────────────────────────────────────────────────────────────
# 阶段 3:再 2 轮对话触发第 2 次摘要(L3 count → 2)
# ────────────────────────────────────────────────────────────────────
banner "阶段 3 / 4 — 触发第 2 次摘要(L3 count → 2)"
echo -e "  预期:L3 count ≥ 2,L4 仍为空(未达 Reflection 阈值 3)"

step "发送 2 轮微服务架构对话(触发第 2 批摘要)"
send_chat "服务网格 sidecar 模式和 SDK 模式在 Go 微服务里如何选型?"
send_chat "Go 服务注册发现用 etcd 还是 consul,各自的 watch 机制有什么差异?"

step "等待异步摘要管道完成..."
sleep 4

show_state "阶段 3 完成 — L3 应出现第 2 篇摘要"
pause

# ────────────────────────────────────────────────────────────────────
# 阶段 4:再 2 轮对话触发第 3 次 consolidation → 达到 reflectionThreshold
#         → 触发 ReflectionWorker → 写入 L4 用户画像
# ────────────────────────────────────────────────────────────────────
banner "阶段 4 / 4 — 触发 Reflection(L4 用户画像激活)"
echo -e "  第 3 次 consolidation 到达阈值,触发 ReflectionWorker"
echo -e "  LLM 跨摘要提炼用户画像,写入 Redis Hash"
echo -e "  预期:L3 count ≥ 3,L4 profile 包含用户技术偏好"

step "发送 2 轮分布式系统对话(触发第 3 批摘要 + Reflection)"
send_chat "Raft 和 Paxos 在分布式一致性上的工程实现复杂度有多大差距?"
send_chat "Go 实现的分布式限流方案,令牌桶和滑动窗口在高 QPS 下的精度对比?"

step "等待 Reflection 完成(含 LLM 跨摘要推理,耗时较长)..."
sleep 6

show_state "阶段 4 完成 — L4 用户画像应已生成"

# ────────────────────────────────────────────────────────────────────
# 完成
# ────────────────────────────────────────────────────────────────────
banner "Demo 完成"
echo ""
echo -e "  ${GREEN}✓ 完整 L1→L2→L3→L4 管道演示结束${NC}"
echo ""
echo -e "  后续操作:"
echo -e "  ${DIM}# 清理本次 session 数据${NC}"
echo -e "  curl -X DELETE $BASE_URL/api/v1/debug/memory/$SESSION_ID"
echo ""
echo -e "  ${DIM}# 手动触发 Eviction(清理低重要度/过期文档)${NC}"
echo -e "  curl -X POST $BASE_URL/api/v1/debug/memory/evict"
echo ""
