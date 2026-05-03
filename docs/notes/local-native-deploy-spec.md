# 本机原生部署方案 Spec（无 Docker）

> 目标：完全脱离 Docker Desktop，在 Apple Silicon Mac 上原生运行所有服务，消除 VM 内存开销和发热，并让 embedding 服务使用 Metal GPU（MPS）加速。

---

## 1. 服务清单与部署方式

| 服务 | Docker 镜像 | 本机替代方案 | 备注 |
|---|---|---|---|
| **embedding** | `michaelf34/infinity:0.0.77` | Python venv + `start-embedding-mac.sh` | 已有脚本，享受 MPS |
| **PostgreSQL + pgvector** | `pgvector/pgvector:0.8.0-pg16` | `brew install postgresql@16` + 手动编译 pgvector | 唯一需要编译的步骤 |
| **Redis** | `redis:7` | `brew install redis` | 一行搞定 |
| **Spring Boot app** | 自构建镜像 | `mvn spring-boot:run` 或 `java -jar` | 无需改动代码 |
| **Prometheus** | `prom/prometheus:v3.2.1` | `brew install prometheus` + 修改配置 | 可选，telemetry |
| **Grafana** | `grafana/grafana:11.5.2` | `brew install grafana` | 可选，telemetry |

---

## 2. 前置条件

- macOS Apple Silicon（arm64）
- Homebrew 已安装
- Java 17+（`brew install temurin@17` 或已有，pom.xml 要求 `java.version=17`）
- Maven 3.9+（`brew install maven`）
- Python 3.10+（`brew install python@3.12`）
- Xcode Command Line Tools（`xcode-select --install`，pgvector 编译需要）

---

## 3. 各服务部署细节

### 3.1 PostgreSQL 16 + pgvector 0.8.0

```bash
# 安装 PG16
brew install postgresql@16
brew services start postgresql@16
export PATH="/opt/homebrew/opt/postgresql@16/bin:$PATH"  # 加入 ~/.zshrc

# 编译安装 pgvector
git clone --branch v0.8.0 https://github.com/pgvector/pgvector.git /tmp/pgvector
cd /tmp/pgvector
make
make install  # 自动检测 pg_config 路径并安装到正确位置

# 创建数据库和用户
psql postgres -c "CREATE USER dawn WITH PASSWORD 'dawn123';"
psql postgres -c "CREATE DATABASE dawn_ai OWNER dawn;"
psql dawn_ai -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

验证：
```bash
psql -U dawn -d dawn_ai -c "SELECT extversion FROM pg_extension WHERE extname = 'vector';"
# 期望输出：0.8.0
```

### 3.2 Redis 7

```bash
brew install redis
brew services start redis
```

验证：
```bash
redis-cli ping  # 期望：PONG
```

### 3.3 Embedding（已有脚本）

```bash
./scripts/start-embedding-mac.sh --daemon
```

验证：
```bash
curl -s http://localhost:7997/health  # 期望：{"status":"ok"}
```

### 3.4 Spring Boot App

环境变量需设置（写入 `.env.local` 后 source，或直接 export）：

```bash
export OPENAI_API_KEY=<your-key>
export BASE_URL=<your-base-url>
export CHAT_MODEL=<your-model>
export EMBEDDING_MODEL=BAAI/bge-m3
export EMBEDDING_BASE_URL=http://localhost:7997
export EMBEDDING_API_KEY=local-embedding-key
export EMBEDDING_DIMENSIONS=1024
# PG 和 Redis 用默认 localhost，application.yml 已配置无需额外设置
```

启动：
```bash
mvn spring-boot:run
# 或构建后运行
mvn package -DskipTests
java -jar target/dawn-ai-*.jar
```

### 3.5 Prometheus（可选）

```bash
brew install prometheus
```

需修改 `prometheus.yml`，将 `app:8080` 改为 `localhost:8080`：
```yaml
static_configs:
  - targets: ['localhost:8080']
```

启动：
```bash
prometheus --config.file=/Users/junkangd/projects/dawn-ai/prometheus.yml
```

### 3.6 Grafana（可选）

```bash
brew install grafana
brew services start grafana
# 访问 http://localhost:3000，默认账密 admin/admin
```

---

## 4. 脚本设计

提供两个统一管理脚本，放在 `scripts/` 目录：

### `scripts/local-start.sh`

功能：
1. 检查前置依赖（pg16、redis、java、python3）
2. 按顺序启动：Redis → PostgreSQL → embedding（daemon）→ Spring Boot app
3. 每步启动后健康检查，失败则中止并给出提示
4. 支持 `--skip-infra` 跳过 Redis/PG 启动（已在运行时用）
5. 支持 `--skip-telemetry` 跳过 Prometheus/Grafana

### `scripts/local-stop.sh`

功能：
1. 停止 Spring Boot app（按 PID 文件）
2. 停止 embedding（调用现有 `stop-embedding-mac.sh`）
3. 停止 brew 管理的服务（`brew services stop`）
4. 可选：`--keep-infra` 保留 Redis/PG 运行

---

## 5. 目录结构变化

```
scripts/
  start-embedding-mac.sh   # 已有
  stop-embedding-mac.sh    # 已有
  local-start.sh           # 新增：一键启动所有服务
  local-stop.sh            # 新增：一键停止所有服务
logs/
  embedding.log            # 已有
  embedding.pid            # 已有
  app.log                  # 新增：Spring Boot 日志
  app.pid                  # 新增：Spring Boot PID
```

---

## 6. 与 Docker 方案的配置差异

| 配置项 | Docker | 本机 |
|---|---|---|
| PG host | `postgres` | `localhost` |
| Redis host | `redis` | `localhost` |
| Embedding URL | `http://embedding:7997` | `http://localhost:7997` |
| Prometheus target | `app:8080` | `localhost:8080` |

`application.yml` 默认值已经是 `localhost`，Docker 方案通过环境变量注入覆盖。本机方案无需改 yml，不传对应环境变量即可。

---

## 7. 启动顺序依赖

```
Redis ──────────────────────────────┐
PostgreSQL (+ pgvector extension) ──┤
                                    ▼
                            Spring Boot App
Embedding (daemon) ─────────────────┘
```

app 依赖 Redis + PG + Embedding 均已就绪后启动。

---

## 8. 注意事项

1. **pgvector 编译**：每次升级 Homebrew PG 后需重新 `make install`
2. **PATH**：`postgresql@16` 是 keg-only，需手动加入 `$PATH`，否则 `psql` 找不到
3. **HuggingFace 缓存**：模型文件在 `~/.cache/huggingface`，首次启动 embedding 会下载 bge-m3（约 2.2GB），需要网络
4. **端口冲突**：确保 5432 / 6379 / 7997 / 8080 未被占用
5. **Prometheus 配置**：本机运行时需修改 `prometheus.yml` 的 target host
