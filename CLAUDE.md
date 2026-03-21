# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Start infrastructure (PostgreSQL + Redis)
docker-compose up -d postgres redis

# Run application
export OPENAI_API_KEY=sk-your-key-here
./mvnw spring-boot:run

# Build JAR
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=AgentOrchestratorTest

# Run a single test method
./mvnw test -Dtest=AgentOrchestratorTest#shouldCollectSteps
```

## Architecture

**Request flow:**
```
ChatController
  └── ChatService
        ├── RagService.buildContext()     ← pre-fetch RAG context (soft-coupled, to be replaced by RAG-as-Tool)
        └── AgentOrchestrator.chat()
              ├── StepCollector.init()
              ├── TaskPlanner.plan()        ← separate LLM call, temperature=0.3, generates JSON plan
              ├── chatClient.prompt()
              │     .toolNames(...)         ← Spring AI runs the ReAct loop internally (no explicit while)
              │     .call()
              │         └── ToolExecutionAspect (@Around AOP)  ← intercepts every tool apply()
              │               └── StepCollector.record()
              ├── StepCollector.collect()
              └── StepCollector.clear()     ← always in finally, prevents ThreadLocal leak
```

**Key design decisions:**
- Spring AI's `.toolNames().call()` **is** the ReAct loop — it sends tool schemas to the LLM, executes tool calls, feeds results back, and repeats until the LLM stops calling tools. There is no explicit `while` loop in application code.
- `StepCollector` uses `ThreadLocal` to bridge `ToolExecutionAspect` → `AgentOrchestrator` without coupling them.
- `TaskPlanner` makes an independent LLM call before the main chat to generate a step plan injected into the system prompt. Failure degrades gracefully (returns empty list).
- Tools (`WeatherTool`, `CalculatorTool`) implement `Function<Request, Response>` and are annotated `@Component` + `@Description`. The AOP pointcut `execution(* com.dawn.ai.agent.tools.*.apply(..))` auto-traces all tools in this package.
- `maxSteps` in `application.yml` is currently a soft hint in the system prompt only — not enforced in code.

## Infrastructure

| Service | Port | Purpose |
|---|---|---|
| Spring Boot app | 8080 | REST API |
| PostgreSQL | 5432 | JPA + pgvector (RAG embeddings) |
| Redis | 6379 | Conversation memory (`MemoryService`) |
| Prometheus | 9090 | Metrics scraping |
| Grafana | 3000 | Dashboards (admin/admin123) |

`application.yml` reads `OPENAI_API_KEY` from environment. Without it, `AiAvailabilityChecker` rejects all requests with a clear error before they reach the LLM.

## Adding a New Tool

1. Create `src/main/java/com/dawn/ai/agent/tools/MyTool.java` implementing `Function<MyTool.Request, MyTool.Response>`
2. Annotate with `@Component` and `@Description("...")`
3. Add the bean name to `.toolNames(...)` in `AgentOrchestrator` and to `getToolDescriptions()` — until Issue #1 (ToolRegistry) is implemented, this is manual.

`ToolExecutionAspect` will automatically trace the new tool with zero changes.

## Key Config Properties (`application.yml`)

```yaml
app.ai.react.max-steps: 10        # LLM hint only, not enforced in code
app.ai.react.show-steps: false    # include AgentStep list in ChatResponse
app.ai.react.plan-enabled: true   # toggle TaskPlanner pre-execution planning
app.ai.system-prompt: |           # base system prompt for all sessions
```

## Planned Work

See `docs/action-plan-2026-03-20.md` for the full next-action roadmap.
GitHub Issues: https://github.com/Supremes/dawn-ai/issues

Active work items (P0 first):
- **#1** Dynamic ToolRegistry — remove hardcoded toolNames
- **#2** Streaming SSE endpoint
- **#3** RAG as a Tool (Agentic RAG Level 1)
- **#4** maxSteps hard limit via ToolExecutionAspect
- **#5** Telemetry: token usage, per-tool histogram, error classification
- **#7** Structured Output via `BeanOutputConverter` (replace soft prompt constraints + `extractJson()`)
- **#6** Unit + integration test coverage ≥ 70%
