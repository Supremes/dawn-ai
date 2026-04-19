# SSE Streaming TODO

## Current Tasks

### 1. Reduce Broken Pipe Log Noise in ChatService
**Status**: Not Started  
**Priority**: Low (Non-blocking UX improvement)  
**Description**: 
- When test clients disconnect early (e.g., `curl ... | head`), `ChatService.sendEvent()` logs warning messages for "Broken pipe" and "ResponseBodyEmitter has already completed"
- These are test artifacts, not business failures, but clutter the logs
- Add graceful degradation to silently handle intentional client disconnects

**Implementation**:
- In `ChatService.sendEvent()`, detect client disconnect exceptions (IOException with "Broken pipe" or StateException with "has already completed")
- Log at DEBUG level instead of WARN for these specific cases
- Optionally complete the emitter gracefully to avoid cascading warnings

**Files Affected**:
- `src/main/java/com/dawn/ai/service/ChatService.java`

**Related**:
- Lines 130-135 in ChatService.sendEvent()

---

## Completed Tasks

- [x] Add `thinking` SSE event and frontend thought panel for main stream reasoning
- [x] Add `plan_thinking` SSE event for planner reasoning content  
- [x] Implement fallback `AiSyncResponseCapture` for planner `reasoning_content` extraction
- [x] Add `WebClient` streaming request/response logging via `ExchangeFilterFunction`
- [x] Add chunk-level stream logs in `AgentOrchestrator`
- [x] Fix `KnowledgeSearchTool.Request` optional parameters schema
- [x] Pretty-print full AI HTTP response bodies at DEBUG level
- [x] Remove truncation from AI sync response content logs
- [x] Refactor `TaskPlanner.plan()` to return `PlannerResult` with reasoning
- [x] Update unit tests for `PlannerResult` refactor
- [x] Fix Docker image build after test-compile issues

---

## Optional Future Enhancements

- [ ] Trim or summarize very long `plan_thinking` reasoning before rendering in frontend (if UX becomes overwhelming)
- [ ] Add metrics/observability for SSE event delivery latency
- [ ] Implement backpressure/queueing for high-frequency `thinking` events if stream accumulates
- [ ] Cache partial `reasoning_content` between sessions for similar queries
