## Code Review Summary

**Files reviewed**: 3 files, 543 lines changed  
**Overall assessment**: REQUEST_CHANGES

Reviewed untracked local native deployment files:

- `docs/notes/local-native-deploy-spec.md`
- `scripts/local-start.sh`
- `scripts/local-stop.sh`

Also checked related context: `application.yml`, `docker-compose.yml`, `docker-compose.mac.yml`, `prometheus.yml`, existing embedding scripts, `.gitignore`, and Maven metadata.

---

## Findings

### P0 - Critical

(none)

### P1 - High

1. **[scripts/local-start.sh:146] Startup script mutates tracked `prometheus.yml`**
  - `sed -i.bak "s|app:8080|localhost:8080|g" "$PROM_CFG"` changes the repository's tracked `prometheus.yml` during local startup.
  - Impact: after one native-local run, the Docker Compose Prometheus config is silently changed from Docker-network target `app:8080` to host target `localhost:8080`, so switching back to Docker can break scraping. It also dirties the worktree and creates `prometheus.yml.bak`, which is not ignored.
  - Suggested fix: do not edit the tracked config in place. Generate a local-only config under `logs/` or another ignored temp path, or add a separate committed `prometheus.local.yml` and start Prometheus with that file.

::code-comment{file="scripts/local-start.sh" line="146" severity="P1"}
Do not mutate tracked `prometheus.yml` from a startup script. Use a local generated config or a separate `prometheus.local.yml`; otherwise local startup changes Docker behavior and dirties the worktree.
::

2. **[scripts/local-start.sh:219] App health check timeout still exits successfully**
  - The loop waits for `/actuator/health`, but on the 60th attempt it only logs `warn "App 启动超时..."` and then continues to print service addresses.
  - Impact: automation and users get exit code `0` and a success-looking summary even when the app is not ready. This is especially risky because the script is meant to be a one-key deployment entrypoint.
  - Suggested fix: treat readiness timeout as failure with non-zero exit, and preferably include whether the Java process is still alive. For example, set an `APP_READY` flag and call `error` after the loop if it never became ready.

::code-comment{file="scripts/local-start.sh" line="226" severity="P1"}
Readiness timeout should fail the script instead of warning and continuing. Otherwise callers see a successful local deployment while the app is unavailable.
::

3. **[scripts/local-start.sh:91] PostgreSQL readiness and `psql` commands do not pin the target cluster**
  - `pg_isready -q`, `psql postgres`, and `psql -U dawn -d dawn_ai` do not consistently specify host, port, and binary path.
  - Impact: on a Mac with Postgres.app, another Homebrew version, or an already-running local cluster, the script can skip starting `postgresql@16` and initialize the wrong database cluster, or fail with confusing socket/default-user behavior.
  - Suggested fix: use the Homebrew PG16 binaries explicitly and pin connection parameters, e.g. `"$PG_BIN/pg_isready" -h localhost -p 5432`, `"$PG_BIN/psql" -h localhost -p 5432 -d postgres ...`, and the same target for all role/database/extension checks.

::code-comment{file="scripts/local-start.sh" line="91" severity="P1"}
Pin the intended PostgreSQL target (`$PG_BIN`, host, port, db, user) for readiness checks and initialization. The current defaults can hit a different local cluster.
::

4. **[scripts/local-start.sh:202] Restart path does not verify the old app process actually stopped**
  - The script sends SIGTERM to the old PID and sleeps for 3 seconds, then immediately starts a new Java process.
  - Impact: Spring Boot graceful shutdown can exceed 3 seconds; the new process can fail to bind port 8080, and the later health check reports only a timeout. This creates intermittent restart failures.
  - Suggested fix: reuse the wait loop pattern from `local-stop.sh`, check `kill -0` until the process exits, and fail or force-kill only after a bounded timeout.

::code-comment{file="scripts/local-start.sh" line="202" severity="P1"}
After SIGTERM, wait until the old process exits before starting a new one. A fixed 3-second sleep can leave port 8080 occupied and cause flaky restarts.
::

### P2 - Medium

5. **[scripts/local-stop.sh:55] Pattern-based `pkill -f` can terminate unrelated processes**
  - When PID files are missing, the script falls back to `pkill -f "dawn-ai.*\.jar"`, `pkill -f "infinity_emb"`, and `pkill -f "prometheus --config"`.
  - Impact: on a shared workstation or with multiple checkouts/environments, this can kill unrelated Java, embedding, or Prometheus processes that merely match the pattern.
  - Suggested fix: only kill explicit PIDs written by this tool. If the PID file is missing, show candidate processes with `pgrep -af` and ask the operator to stop them manually, or require an explicit `--force-pattern-kill` escape hatch.

::code-comment{file="scripts/local-stop.sh" line="55" severity="P2"}
Avoid `pkill -f` fallbacks in deployment cleanup. Prefer explicit PID files; otherwise this can kill unrelated matching processes.
::

6. **[scripts/local-start.sh:107] Database init command chaining masks errors and logs the wrong state**
  - The role/database creation uses `check || create && info "已存在，跳过创建"` style chaining.
  - Impact: because Bash evaluates this as `(check || create) && info`, it prints "已存在" even when it just created the resource. More importantly, failures inside the `|| ... && ...` list are harder to reason about under `set -e`, reducing error clarity during DB setup.
  - Suggested fix: replace both role and database creation blocks with explicit `if ...; then ... else ... fi` logic, and emit separate "created" vs "already exists" messages.

::code-comment{file="scripts/local-start.sh" line="107" severity="P2"}
Use explicit `if/else` for role/database creation. The current `check || create && info` chain misreports created resources as "already exists" and makes failures less clear.
::

7. **[scripts/local-start.sh:134] Embedding readiness is delegated to a script that can return success before the service is ready**
  - `local-start.sh` calls `start-embedding-mac.sh --daemon` but does not independently verify `/health` afterward.
  - Impact: the existing embedding script warns but exits successfully if the service is still initializing after its timeout. `local-start.sh` can then continue and start the app while a required dependency is unavailable.
  - Suggested fix: after calling the embedding script, perform a local-start-owned health check loop and fail if `http://localhost:$EMBED_PORT/health` never becomes ready within the deployment timeout.

::code-comment{file="scripts/local-start.sh" line="134" severity="P2"}
Re-check embedding readiness in `local-start.sh` after daemon start. Do not rely on a helper script that can exit 0 while the service is still unavailable.
::

8. **[docs/notes/local-native-deploy-spec.md:145] Stop-script contract in the spec does not match the implementation**
  - The spec says `local-stop.sh` stops brew-managed services and supports `--keep-infra`. The actual script keeps Redis/PostgreSQL by default and uses `--all` to stop them.
  - Impact: users following the spec may expect infra to stop by default or try a nonexistent `--keep-infra` flag.
  - Suggested fix: align the spec with the implemented contract, or change the script to match the spec. The safer local-dev default is the current script behavior: keep infra by default, stop it only with `--all`.

::code-comment{file="docs/notes/local-native-deploy-spec.md" line="145" severity="P2"}
The documented stop behavior and flags differ from the script. Align the spec with `--all` / default keep-infra semantics, or update the implementation.
::

### P3 - Low

9. **[scripts/local-start.sh:22] Unknown CLI flags are silently ignored**
  - A typo such as `--skip-telemety` is accepted and then telemetry still starts, including the current tracked-config mutation behavior.
  - Suggested fix: add a default `*) error "未知参数: $arg" ;;` branch to both scripts' argument parsers.

10. **[scripts/local-start.sh:109] Local database password is hardcoded in commands and docs**
  - This matches existing local defaults in the project, so it is not a new production secret by itself.
  - Suggested fix: if this is meant to be reused beyond personal local development, prefer `DAWN_DB_PASSWORD="${DAWN_DB_PASSWORD:-dawn123}"` and document it as local-only.

---

## Removal/Iteration Plan

No safe deletion candidates in this change set. Recommended iteration order:

1. Remove startup side effects by introducing a separate local Prometheus config.
2. Make readiness failures non-zero and verify old process shutdown before restart.
3. Pin PostgreSQL connection target and rewrite DB init as explicit `if/else`.
4. Remove unsafe pattern-based kill fallbacks or guard them behind an explicit force flag.
5. Align the spec with actual script flags and behavior.

## Additional Suggestions

- Keep the native-local path and Docker path independent: scripts should not rewrite files used by Docker Compose.
- Add a lightweight smoke command after successful startup, such as one `curl` to `/actuator/health`, and make it the final success gate.
- If Prometheus/Grafana are optional, consider defaulting local startup to `--skip-telemetry` semantics unless explicitly requested; this reduces Homebrew service side effects.
