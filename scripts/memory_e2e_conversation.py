#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#   "requests",
# ]
# ///
"""
dawn-ai · Memory Management E2E 对话验证脚本
============================================

通过真实 HTTP API 驱动多轮对话，验证四层记忆管道的完整链路。
无需 JUnit / Mockito / 测试框架，使用真实 Redis + PGVector。

管道层级（e2e-test 配置）：
  L1  Redis 滑动窗口      MAX_HISTORY = 20 条
  L2  Pending 摘要化      batch-size  = 3  条
  L3  VectorStore 情节    reflection-threshold = 3 次
  L4  UserProfile 画像    episode-threshold    = 4 条

触发规律（从第 21 条开始 overflow）：
  注入 23 条 → 3 次 overflow → 1 batch → L3 写入 1 次
  注入 26 条 → 6 次 overflow → 2 batch → L3 写入 2 次
  注入 29 条 → 9 次 overflow → 3 batch → L3 写入 3 次 → Reflection → L4 写入

前置条件：
  1. docker compose up -d   (Redis:6379 + PostgreSQL/PGVector:5432)
  2. mvn spring-boot:run -Dspring-boot.run.profiles=e2e-test
     （或 java -jar target/dawn-ai.jar --spring.profiles.active=e2e-test）

运行：
  python3 scripts/memory_e2e_conversation.py [http://localhost:8080]
"""

import sys
import time
import uuid
import json
import requests
from datetime import datetime

# ─── 配置 ──────────────────────────────────────────────────────────────────
BASE = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8080"
MEMORY_API = f"{BASE}/api/v1/debug/memory"

# ─── ANSI 颜色 ──────────────────────────────────────────────────────────────
GREEN  = "\033[92m"
RED    = "\033[91m"
YELLOW = "\033[93m"
CYAN   = "\033[96m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

# ─── 全局计数器 ──────────────────────────────────────────────────────────────
_passed = 0
_failed = 0
_start  = time.time()


# ═══════════════════════════════════════════════════════════════════════════
#  断言辅助
# ═══════════════════════════════════════════════════════════════════════════

def _ok(label, detail=""):
    global _passed
    _passed += 1
    suffix = f"  {DIM}({detail}){RESET}" if detail else ""
    print(f"  {GREEN}✅ {label}{suffix}{RESET}")


def _fail(label, detail=""):
    global _failed
    _failed += 1
    suffix = f"  {YELLOW}({detail}){RESET}" if detail else ""
    print(f"  {RED}❌ {label}{suffix}{RESET}")


def assert_eq(label, actual, expected):
    if actual == expected:
        _ok(label, f"= {actual}")
    else:
        _fail(label, f"actual={actual!r}, expected={expected!r}")


def assert_gte(label, actual, minimum):
    if actual >= minimum:
        _ok(label, f"{actual} ≥ {minimum}")
    else:
        _fail(label, f"{actual} < {minimum}")


def assert_lte(label, actual, maximum):
    if actual <= maximum:
        _ok(label, f"{actual} ≤ {maximum}")
    else:
        _fail(label, f"{actual} > {maximum}")


def assert_truthy(label, condition, detail=""):
    if condition:
        _ok(label, detail)
    else:
        _fail(label, detail)


# ═══════════════════════════════════════════════════════════════════════════
#  HTTP 封装
# ═══════════════════════════════════════════════════════════════════════════

def inject(sid: str, count: int, template: str = "消息 {i}", role: str = "user") -> dict:
    """批量注入消息到 MemoryService，触发 L1→L2→L3 管道。"""
    r = requests.post(
        f"{MEMORY_API}/{sid}/inject",
        json={"count": count, "template": template, "role": role},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def inject_doc(sid: str, content: str, type_: str = "summary",
               importance: float = 0.5, age_days: int = 0) -> dict:
    """直接向 VectorStore 写入文档（跳过摘要管道，用于 Eviction 测试）。"""
    r = requests.post(
        f"{MEMORY_API}/{sid}/inject-doc",
        json={"content": content, "type": type_,
              "importance": importance, "ageDays": age_days},
        timeout=10,
    )
    r.raise_for_status()
    return r.json()


def state(sid: str) -> dict:
    """查询 session 的四层记忆状态。"""
    r = requests.get(f"{MEMORY_API}/{sid}/state", timeout=10)
    r.raise_for_status()
    return r.json()


def trigger_evict():
    """手动触发驱逐策略。"""
    r = requests.post(f"{MEMORY_API}/evict", timeout=30)
    r.raise_for_status()


def cleanup(sid: str):
    """清理 session 的 Redis 数据（VectorStore 文档依赖 sessionId 隔离）。"""
    requests.delete(f"{MEMORY_API}/{sid}", timeout=10)


def wait_until(sid: str, predicate, timeout: int = 15, label: str = "") -> dict:
    """
    轮询 state(sid) 直到 predicate 返回 True 或超时。
    超时时记录为失败断言，返回最终状态。
    """
    deadline = time.time() + timeout
    last = {}
    while time.time() < deadline:
        try:
            s = state(sid)
            last = s
            if predicate(s):
                return s
        except Exception:
            pass
        time.sleep(0.5)
    _fail(f"等待超时：{label}", f"最终状态={last}")
    return last


def section(title: str):
    print(f"\n{CYAN}{BOLD}─── {title} ───{RESET}")


# ═══════════════════════════════════════════════════════════════════════════
#  健康检查
# ═══════════════════════════════════════════════════════════════════════════

def health_check():
    section("健康检查")
    try:
        r = requests.get(f"{BASE}/actuator/health", timeout=5)
        assert_truthy(
            f"服务在 {BASE} 正常运行",
            r.status_code == 200,
            f"HTTP {r.status_code}"
        )
        body = r.json()
        assert_truthy(
            "服务状态 = UP",
            body.get("status") == "UP",
            str(body.get("status"))
        )
    except requests.exceptions.ConnectionError as e:
        _fail("服务连接失败，请先启动 Spring Boot（--spring.profiles.active=e2e-test）", str(e))
        sys.exit(1)


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 1 · L1 Redis 滑动窗口
# ═══════════════════════════════════════════════════════════════════════════

def phase1_l1_sliding_window():
    section("PHASE 1 · L1 · Redis 滑动窗口")
    sid = f"e2e-l1-{uuid.uuid4().hex[:8]}"
    print(f"  {DIM}sessionId={sid}{RESET}")

    # 1.1 基础读写
    inject(sid, 5, template="你好，我在学习 Go 语言并发，这是第 {i} 个问题")
    s = state(sid)
    assert_eq("1.1 注入 5 条 → L1 窗口 = 5", s["l1Window"], 5)

    # 1.2 填满窗口
    inject(sid, 15, template="Go channel 进阶使用场景 {i}")
    s = state(sid)
    assert_eq("1.2 共 20 条 → L1 窗口 = 20（已填满）", s["l1Window"], 20)

    # 1.3 触发 overflow：第 21~23 条挤入 pending，凑成 1 个 batch
    inject(sid, 3, template="goroutine 调度深度剖析 {i}")
    s = state(sid)
    assert_lte("1.3 注入第 21~23 条后 L1 窗口 ≤ 20（滑动窗口生效）", s["l1Window"], 20)

    # 1.4 等待 L3 首次 consolidation（batch=3，已触发）
    final = wait_until(
        sid,
        lambda s: s["l3Count"] >= 1,
        timeout=15,
        label="首次 consolidation 写入 PGVector",
    )
    assert_gte("1.4 L3 首次摘要已写入 PGVector（l3Count ≥ 1）", final["l3Count"], 1)

    cleanup(sid)
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 2 · L2→L3 摘要管道验证
# ═══════════════════════════════════════════════════════════════════════════

def phase2_l2_l3_pipeline():
    section("PHASE 2 · L2→L3 · 异步摘要写入 PGVector")
    sid = f"e2e-l2l3-{uuid.uuid4().hex[:8]}"
    print(f"  {DIM}sessionId={sid}{RESET}")

    # 注入 29 条：20 填满 L1 + 9 overflow = 3 个 batch = 3 次 consolidation
    print(f"  {DIM}注入 20 条填满 L1...{RESET}")
    inject(sid, 20, template="微服务架构设计：服务拆分与边界 第{i}轮")

    print(f"  {DIM}注入 3 条 → batch 1 → consolidation #1...{RESET}")
    inject(sid, 3, template="gRPC 双向流与背压控制 第{i}节")

    print(f"  {DIM}注入 3 条 → batch 2 → consolidation #2...{RESET}")
    inject(sid, 3, template="Kafka 消息幂等与精确一次语义 第{i}节")

    print(f"  {DIM}注入 3 条 → batch 3 → consolidation #3...{RESET}")
    inject(sid, 3, template="Redis 分布式锁与 Redlock 算法 第{i}节")

    # 等待 3 次 consolidation 全部写入
    final = wait_until(
        sid,
        lambda s: s["l3Count"] >= 3,
        timeout=20,
        label="3 次 consolidation 均写入 PGVector",
    )
    assert_gte("2.1 3 次摘要全部写入 PGVector（l3Count ≥ 3）", final["l3Count"], 3)
    assert_lte("2.2 L1 活跃窗口始终 ≤ 20", final["l1Window"], 20)
    assert_eq("2.3 L2 Pending 已清空（batch 后 drain）", final["l2Pending"], 0)

    cleanup(sid)
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 3 · L3→L4 Reflection 与用户画像
# ═══════════════════════════════════════════════════════════════════════════

def phase3_l4_reflection():
    section("PHASE 3 · L3→L4 · Reflection 与用户画像写入")
    sid = f"e2e-l4-{uuid.uuid4().hex[:8]}"
    print(f"  {DIM}sessionId={sid}{RESET}")
    print(f"  {DIM}reflection-threshold=3：第 3 次 consolidation 触发 ReflectionEvent{RESET}")

    # 模拟一段真实技术讨论对话（user/assistant 交替）
    conversation_pairs = [
        ("请详细介绍 PostgreSQL 的 MVCC 并发控制机制",  "MVCC 通过版本链实现，每个事务只读取自己可见的版本"),
        ("如何选择合适的隔离级别？",                    "OLTP 建议 READ COMMITTED，报表场景用 REPEATABLE READ"),
        ("索引策略如何优化高并发写入？",                "部分索引 + BRIN 索引适合时序数据，减少锁竞争"),
        ("分区表在大数据量下的收益？",                  "Range 分区按时间归档，查询裁剪可降低扫描量 90%+"),
        ("连接池如何配置最优参数？",                    "max_connections = CPU核数×4，pool_size = 核数×2"),
        ("如何监控慢查询？",                            "pg_stat_statements + auto_explain 记录执行计划"),
        ("WAL 日志如何影响性能？",                      "synchronous_commit=off 可提升写吞吐，但有丢失风险"),
        ("如何做在线 Schema 变更？",                    "pg_repack 或 pt-online-schema-change 避免长锁"),
        ("向量搜索 pgvector 的实际性能？",              "HNSW 索引 1M 条记录 P99 < 50ms，余弦距离精度 99%+"),
    ]

    # 填满 L1（发 20 条 user 消息）
    inject(sid, 20, template="数据库深度探讨 第 {i} 轮问题")

    # 发送真实对话（9对=18条，触发3个batch）
    for i, (user_msg, asst_msg) in enumerate(conversation_pairs, 1):
        inject(sid, 1, template=user_msg, role="user")
        inject(sid, 1, template=asst_msg, role="assistant")

    # 等待 L3 ≥ 3（触发 ReflectionEvent）
    print(f"  {DIM}等待 3 次 consolidation 完成...{RESET}")
    wait_until(sid, lambda s: s["l3Count"] >= 3, timeout=20,
               label="3 次 consolidation 完成")

    # 等待 L4 Reflection 写入（ReflectionWorker 是独立异步）
    print(f"  {DIM}等待 ReflectionWorker 写入用户画像...{RESET}")
    final = wait_until(
        sid,
        lambda s: bool(s.get("l4Profile", {}).get("reflection")),
        timeout=20,
        label="L4 Reflection 写入用户画像",
    )

    assert_truthy(
        "3.1 L4 用户画像含 reflection 字段（ReflectionWorker 写入）",
        bool(final.get("l4Profile", {}).get("reflection")),
        f"profile={final.get('l4Profile', {})}",
    )
    # reflection 文档也写入了 VectorStore（type=reflection，importance=0.9）
    assert_gte("3.2 PGVector 含 summary + reflection 文档（l3Count ≥ 4）",
               final["l3Count"], 4)

    cleanup(sid)
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 4 · Eviction 驱逐策略
# ═══════════════════════════════════════════════════════════════════════════

def phase4_eviction():
    section("PHASE 4 · Eviction · 驱逐策略验证")

    # 4.1 低重要度 + 超龄文档 → 被驱逐
    print(f"\n  {DIM}4.1 低重要度超龄文档...{RESET}")
    sid_stale = f"e2e-stale-{uuid.uuid4().hex[:8]}"
    inject_doc(
        sid_stale,
        content="早已过时的话题：PHP + FTP 手动部署流程",
        type_="summary",
        importance=0.05,
        age_days=200,
    )
    wait_until(sid_stale, lambda s: s["l3Count"] >= 1, timeout=5,
               label="低重要度文档写入 PGVector")

    trigger_evict()
    time.sleep(1)  # 等待 PG 事务提交

    s = state(sid_stale)
    assert_eq("4.1 低重要度超龄文档被驱逐（l3Count=0）", s["l3Count"], 0)

    # 4.2 高重要度 + 超龄文档 → 保留
    print(f"  {DIM}4.2 高重要度超龄文档...{RESET}")
    sid_high = f"e2e-high-{uuid.uuid4().hex[:8]}"
    inject_doc(
        sid_high,
        content="核心架构决策：拆分为 12 个微服务域，领域驱动设计",
        type_="summary",
        importance=0.9,
        age_days=200,
    )
    trigger_evict()
    time.sleep(1)

    s = state(sid_high)
    assert_gte("4.2 高重要度文档不被驱逐（l3Count ≥ 1）", s["l3Count"], 1)

    # 4.3 reflection 文档（importance 极低）→ 永不删除
    print(f"  {DIM}4.3 reflection 文档（低 importance）...{RESET}")
    sid_refl = f"e2e-refl-{uuid.uuid4().hex[:8]}"
    inject_doc(
        sid_refl,
        content="用户画像反思：用户是 Go/Java 全栈工程师，专注分布式系统",
        type_="reflection",
        importance=0.03,   # 低于 threshold 但 type=reflection → 永不删
        age_days=365,
    )
    trigger_evict()
    time.sleep(1)

    s = state(sid_refl)
    assert_gte("4.3 reflection 文档永不被驱逐（l3Count ≥ 1）", s["l3Count"], 1)

    cleanup(sid_stale)
    cleanup(sid_high)
    cleanup(sid_refl)
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  PHASE 5 · 全链路整合：29 轮真实技术对话
# ═══════════════════════════════════════════════════════════════════════════

def phase5_full_pipeline():
    section("PHASE 5 · Full Pipeline · 29 轮技术对话驱动全链路")
    sid = f"e2e-full-{uuid.uuid4().hex[:8]}"
    print(f"  {DIM}sessionId={sid}{RESET}")

    # 模拟一次真实的系统设计咨询对话（user + assistant 交替，共 29 对）
    conversation = [
        ("user",      "你好，我正在设计高并发订单系统，需要你帮我做技术选型"),
        ("assistant", "很高兴帮助你！请告诉我预期 QPS 和一致性要求"),
        ("user",      "预期峰值 QPS 5 万，订单不能丢，需要支持消息回放"),
        ("assistant", "推荐 Kafka 做消息队列，支持持久化和消费组独立回放"),
        ("user",      "Kafka 分区策略用订单 ID 还是用户 ID？"),
        ("assistant", "按订单 ID 取模分区，保证同一订单的消息严格有序"),
        ("user",      "数据库层如何设计？需要支持写多读多"),
        ("assistant", "建议 PostgreSQL 主库 + 只读副本，写在主库，报表走副本"),
        ("user",      "连接池方案？"),
        ("assistant", "PgBouncer transaction mode，pool size = CPU核数 × 2，最稳定"),
        ("user",      "缓存层选型？Redis 还是 Memcached？"),
        ("assistant", "Redis 6.2+ 支持 Stream、Pub/Sub，功能远超 Memcached"),
        ("user",      "如何防止缓存雪崩和穿透？"),
        ("assistant", "过期时间加随机抖动 + 布隆过滤器防穿透 + 限流熔断兜底"),
        ("user",      "分布式锁选型？"),
        ("assistant", "轻量场景 Redis Redlock，强一致场景 ZooKeeper 临时节点"),
        ("user",      "服务间通信用什么？"),
        ("assistant", "内部 gRPC + 外部 REST Gateway，gRPC 在内网延迟可控在 5ms 以内"),
        ("user",      "链路追踪方案？"),
        ("assistant", "OpenTelemetry 自动埋点 + Jaeger，Trace ID 贯穿全链路"),
        ("user",      "监控告警如何搭建？"),
        ("assistant", "Prometheus + Grafana，P99 延迟 SLO < 200ms，超阈值触发 PagerDuty"),
        ("user",      "K8s 部署策略？"),
        ("assistant", "蓝绿发布用于回滚，金丝雀发布用于新功能验证，Argo Rollouts 管理"),
        ("user",      "CI/CD 流水线如何设计？"),
        ("assistant", "GitHub Actions：PR 触发单测 → 合并触发镜像构建 → 自动部署 Staging → 手动审批 Prod"),
        ("user",      "代码质量管控？"),
        ("assistant", "SonarQube 扫描 + 覆盖率门禁 ≥ 80% + 强制 Code Review 2 人"),
        ("user",      "技术债务如何管控？"),
        ("assistant", "每 Sprint 保留 20% 容量还债，SonarQube Technical Debt Ratio 指标量化"),
        ("user",      "混沌工程如何入门？"),
        ("assistant", "从 ChaosBlade 开始，先在 Staging 注入网络延迟 200ms，验证降级策略"),
        ("user",      "容量规划方法论？"),
        ("assistant", "压测 → 找瓶颈 → 计算水位线 → 预留 30% buffer → 结合增长预测动态调整"),
        ("user",      "这套方案整体感觉非常完整，Go 还是 Java 更合适？"),
        ("assistant", "团队 Java 为主就选 Java，Spring Boot 生态成熟，招人也更容易"),
        ("user",      "好的，我们团队 Java 为主，坚定选 Spring Boot 方案"),
        ("assistant", "Spring Boot + Spring Cloud + Kafka + Redis + PostgreSQL 是黄金组合，祝项目顺利！"),
        ("user",      "最后，可以帮我梳理一下核心架构决策清单吗？"),
        ("assistant", "当然：① Kafka 分区策略 ② PgBouncer 连接池 ③ Redis Redlock 分布式锁 ④ gRPC 内部通信 ⑤ Argo Rollouts 金丝雀发布"),
    ]

    print(f"  {DIM}发送 {len(conversation)} 条对话消息（user+assistant 交替）...{RESET}")
    for role, content in conversation:
        inject(sid, 1, template=content, role=role)

    # L1 验证
    s = state(sid)
    assert_lte("5.1 L1 活跃窗口 ≤ 20（滑动窗口一直生效）", s["l1Window"], 20)

    # 等待 L3 ≥ 3（29条 = 20填L1 + 9 overflow = 3 batch = 3 consolidations）
    print(f"  {DIM}等待 3 次 consolidation 写入 PGVector...{RESET}")
    final = wait_until(
        sid,
        lambda s: s["l3Count"] >= 3,
        timeout=25,
        label="3 次 consolidation 写入 PGVector",
    )
    assert_gte("5.2 PGVector 中 ≥ 3 条 summary 文档（L3 持久化）", final["l3Count"], 3)

    # 等待 L4 Reflection（第 3 次 consolidation 触发 ReflectionEvent）
    print(f"  {DIM}等待 ReflectionWorker 合成用户画像...{RESET}")
    final = wait_until(
        sid,
        lambda s: bool(s.get("l4Profile", {}).get("reflection")),
        timeout=25,
        label="L4 用户画像写入（reflection 字段）",
    )
    reflection_text = final.get("l4Profile", {}).get("reflection", "")
    assert_truthy(
        "5.3 L4 用户画像写入成功（reflection 字段非空）",
        bool(reflection_text),
    )
    assert_truthy(
        "5.4 L1→L2→L3→L4 全链路贯通",
        final["l1Window"] <= 20
        and final["l3Count"] >= 3
        and bool(reflection_text),
        f"l1={final['l1Window']} l3={final['l3Count']} l4=有",
    )

    print(f"\n  {DIM}用户画像内容（节选）：{reflection_text[:80]}...{RESET}")

    cleanup(sid)
    print()


# ═══════════════════════════════════════════════════════════════════════════
#  主流程
# ═══════════════════════════════════════════════════════════════════════════

def main():
    print(f"""{BOLD}{CYAN}
╔═══════════════════════════════════════════════════════════╗
║    dawn-ai · Memory Management E2E 对话验证脚本           ║
║    目标服务：{BASE:<46}║
║    时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S'):<49}║
╚═══════════════════════════════════════════════════════════╝{RESET}
""")

    health_check()
    phase1_l1_sliding_window()
    phase2_l2_l3_pipeline()
    phase3_l4_reflection()
    phase4_eviction()
    phase5_full_pipeline()

    elapsed = time.time() - _start
    total = _passed + _failed
    color = GREEN if _failed == 0 else RED

    print(f"""{BOLD}{CYAN}
╔═══════════════════════════════════════════════════════════╗
║    {color}{'✅ 全部通过' if _failed == 0 else f'❌ {_failed} 项失败'}{CYAN}{'':20}║
║    测试结果：{color}{_passed}/{total} 通过{CYAN}{'':39}║
║    总耗时：{elapsed:.1f}s{'':50}║
╚═══════════════════════════════════════════════════════════╝{RESET}
""")

    sys.exit(0 if _failed == 0 else 1)


if __name__ == "__main__":
    main()
