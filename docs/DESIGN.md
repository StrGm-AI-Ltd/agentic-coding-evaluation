# Agentic Coding Evaluation (ACE) — Design

This is the design reference for `ace-service`, the Java 21 / Spring Boot port of the original
Python `agentbench-trading` CLI harness, plus `ace-ui-vaadin`, its Vaadin web front end. It describes
what the harness measures, how the scoring and runner mechanics actually work in this codebase, what
has and has not been carried over from the original design, and the capabilities this port has since
grown natively, with no Python precedent at all (Judge, cross-task lesson sharing, operator
intervention, the Vaadin UI itself). Claims below were checked against the real source in
`ace-service/src/main/java/com/strgmai/ace/service/` and `ace-ui-vaadin/src/main/java/com/strgmai/ace/ui/`
— where something could not be verified against the code, or is known to differ from the original
design, that is called out explicitly rather than asserted.

## 1. Purpose and the two questions it answers

Rank **local (or hosted) open-source LLMs** at **agentic coding** — driving a coding agent to build a
small trading platform: Java 21 / Spring Boot 3 / Gradle / PostgreSQL + Flyway / React / OpenAPI /
Docker Compose microservices. Two questions the harness is built to answer:

1. **Model A/B**: same harness, same task — which model scores higher, at what cost?
2. **Harness effect**: same model — does the way the agent's context is managed change the outcome?

In the original Python project this second question turned out to be the decisive one (an
orchestrated, task-by-task harness beat a single growing session for a weak local model). That
finding is *why* this harness is shaped the way it is; ace-service ports the same two-mode design
(§13) rather than re-deriving it.

In practice both questions serve the same end for an operator: which model, harness mode, and
sampler/budget hyperparameters actually get a given task done well on the hardware (or provider)
they actually have — not which model wins an abstract leaderboard. `ace-ui-vaadin`'s Queue sets any
of those per job; Experiments (§14) runs a templated, repeated batch across one of the two questions
above and reports a verdict once every job finishes.

## 2. Repository layout

```
ace-service/
  src/main/java/com/strgmai/ace/service/
    AceServiceApplication.java       Spring Boot entry point (Vaadin-less REST + scheduled worker)
    agent/       ReferenceAgent (in-process, LangChain4j) and PiAgent (external subprocess) — the
                 two selectable harnesses (RunSpec.HARNESSES = "ref"/"pi", see §7/§8); AgentTools,
                 AgentSession, CompactionStrategies
    config/      BenchProperties (@ConfigurationProperties(prefix="ace")), JsonColumns (jOOQ JSON binding)
    docker/      DockerService — passive Docker-running probe, CLI-plugin mirroring into a fresh HOME
    metrics/     Collect, Trajectory, StatsService — cost, trajectory analysis, k-run statistics
    oracle/      CheckId (the 30-check registry), CheckStatus, CheckResult, Scorer, RunOracle
    oracle/checks/  StructureChecks, MoneySafetyChecks, BuildChecks, ComposeChecks, BlackboxScenarios
    pack/        Packs — stable pack, task pack, review/trajectory-review packs, parallel-plan pack, fix pack
    plan/        PlanParser, PlanTask, PlanError — plan-text parsing into topologically ordered tasks
    proxy/       RecordingProxy (journals every request/response), SseAssembler
    reference/   TradingService (+ 9 seeded bugs), TradingController, ReferenceServer (standalone twin)
    runner/      RunBench (orchestrator), RunBenchSupport, ContextProbe, Validity, VerifyTask,
                 JournalFacts, Reviews, RecordingProxyFactory, Judge (mid-task trajectory
                 checkpoint, §9), Intervene + PauseAfterTask (operator controls, §11), TaskMatcher
                 (cross-run semantic task matching for Compare, §14)
    service/     JobQueue, WorkerService, ExperimentsService, ImporterService, BenchController,
                 Preflight, RunSpec, Tailer, TreatmentPin, ApiExceptionHandler, JobCfgFactory
                 (argv → job cfg extraction), TaskTranscript (Live-tab transcript rendering)
  src/main/resources/
    application.yml, db/migration/V1..V6__*.sql     Flyway migrations for the service's own SQLite store
    task/PROMPT.md, tasks/PROMPT.md, tasks/ladder.json, tasks/L1..L7_*/PROMPT.md   the frozen task specs
    contract/openapi.yaml            machine-readable twin of the frozen HTTP contract
  fixtures/positive/, fixtures/adversarial/cheat{A-F}_*    gaming-resistance fixtures — see §6
  review.txt, review_fixes.txt       this port's own adversarial code review (66 findings, all fixed)

ace-ui-vaadin/
  src/main/java/com/strgmai/ace/ui/  Vaadin 25 views + SSE-driven live loops — see §14
```

## 3. Scoring

`oracle/CheckId.java` is the single source of truth, and it is a faithful, checked port: **30 checks**
across 6 categories, same ids and same weights as the original registry (structure S1–S9 weight 1
each, planning P1–P3 weight 2 each, lint M1–M4 weight 1 each, build B1–B3 weight 3 each, runtime C1
weight 3 / C2 weight 2, functional F1–F5 weight 5 / F6 weight 4, bespoke F7–F9 weight 5 each — total
weight 77, matching the original L7 denominator exactly).

| category | ids | weight each | what |
|---|---|---|---|
| structure | S1–S9 | 1 | task docs, compose file, ≥3 real Gradle services, real wrapper, parseable OpenAPI, React in dependencies, DB migrations |
| planning | P1–P3 | 2 | definition sections; subtasks with id/goal/deps/criterion; no source written during phase 0/1 |
| lint | M1–M4 | 1 | money never float/double; BigDecimal imported where money is handled; no `equals()` on money; point-in-time replay subtracts sells |
| build | B1–B3 | 3 | compiles in the pinned JDK container; >0 tests executed and passing (JUnit-XML verified); the agent's own suite fails on a seeded mutation |
| runtime | C1, C2 | 3, 2 | ≥3 non-db services report healthy; declared readiness endpoint returns 200 |
| functional | F1–F6 | 5,5,5,5,5,4 | buy/sell symmetry; point-in-time query; insufficient-balance 422; illegal-transition 409; exact decimal round-trip; OpenAPI conformance |
| bespoke | F7–F9 | 5 | HALF_EVEN deposit rounding; exclusive `asOf` boundary with echo; `Idempotency-Key` repeat → same order, 200, no re-execution |

`CheckStatus` (`oracle/CheckStatus.java`) keeps the same five-value model as the original: `PASS`,
`FAIL`, `NOT_ATTEMPTED` (feature simply absent — still counts as FAIL), `SKIPPED` (infra unavailable
before the check ran), `INFRA` (infra failed mid-check). `SKIPPED`/`INFRA` are excluded from the
denominator but still block a full-score headline, exactly as in the original design; both `parse()`
methods fail closed (an unparsable or null status is `FAIL`, never silently dropped).

Per-rung denominators are ported into `tasks/ladder.json` and verified to match: e.g.
`L3p_point_in_time` denominator 36 over `{M1,M2,M4,P2,B1,B2,B3,C2,F1,F2,F3,F8}`, `L7_full_platform`
"all" checks for the full 77. `functional_score_pct` (the F-ids, denominator 44) remains the primary
number; `weighted_score_pct` the composite — this two-score convention is unchanged.

## 4. The frozen contract and the black-box suite

`src/main/resources/task/PROMPT.md` is the frozen task specification handed to the agent, carried
over close to verbatim: the same phased structure (Phase 0 definition, Phase 1 plan, Phase 2..N
implementation), the same hard constraints table (Java 21 / Spring Boot 3.x / Gradle, PostgreSQL +
Flyway, React frontend, OpenAPI 3.1, Docker Compose, ≥3 microservices), and the same frozen HTTP
contract (`POST /accounts`, deposits with HALF_EVEN rounding, orders with idempotent-repeat and
422/409 semantics, `asOf`-scoped holdings, fixed price table, health). One thing worth flagging
precisely because it is easy to miss: the test-environment env-var names in this prompt are **not**
the harness's own `ACE_*` names — the prompt tells the agent to expect
`SPRING_DATASOURCE_URL/USERNAME/PASSWORD` **and** `AB_DB_HOST/PORT/NAME/USER/PASSWORD`, and those
`AB_DB_*` names are deliberately left unrenamed in `oracle/checks/BuildChecks.java` (which sets them
on the agent's own build container) — this is a frozen, agent-facing contract, and renaming it would
silently break every already-graded solution's expectations. The prompt itself says "Do not edit
anything under `task/`."

`oracle/checks/BlackboxScenarios.java` is the port of the black-box HTTP suite that drives that
contract against the agent's running stack. `reference/ReferenceServer.java` (a plain
`com.sun.net.httpserver.HttpServer`, wrapping the same `TradingService` the real Spring
`TradingController` serves) is the calibration twin, and it carries the same **nine seeded bugs** as
the original — verified directly in `TradingService.KNOWN_BUGS`: `float`, `selladd`, `no409`,
`no422`, `asym`, `halfup`, `inclusive`, `noidem`, `hollow`. A dedicated
`BlackboxCalibrationTest.java` exercises this (see §6 for what calibration coverage does and does not
exist beyond that unit test).

## 5. The other oracle checks

`oracle/checks/` carries `StructureChecks`, `MoneySafetyChecks`, `BuildChecks`, `ComposeChecks` and
`BlackboxScenarios` — a 1:1 mapping onto the original's `01..06_*.py` files, minus the standalone
`_common.py` (its helpers — root-first compose discovery, the INFRA classifier, YAML parsing — are
folded into the check classes themselves rather than kept as a separate shared module; this wasn't
checked line-by-line against the original for behavioural parity, only confirmed to exist in some
form). `BuildChecks` in particular carries the mutation-testing logic (B3: mutate the arithmetic
nearest a `SELL` token, require the agent's own suite to catch it) and the Gradle-cache mount at
`~/.cache/ace-service/gradle`.

## 6. Gaming resistance — what exists in this port

The original Python harness's gaming resistance rested on three legs: (1) a fixed-denominator
scoring design (ported, §3), (2) a **positive control** fixture (a known-good solution that must
score ~100%, used to find the oracle's own bugs), and (3) a set of adversarial "cheat" fixtures with
a two-sided selftest asserting they score below a ceiling. All three are now ported:
`ace-service/fixtures/positive/` is a real, substantive known-good solution (account-service,
gateway, build config, docs); `ace-service/fixtures/adversarial/cheat{A-F}_*` is six adversarial
fixtures, each named for the specific gaming pattern it exercises (comment-only changes, bugs hidden
in the test package, renamed fields, a directory merely named `java`, stub implementations with no
real Java, a prebuilt jar substituted for source); `SelftestIT.java`
(`ace-service/src/test/java/com/strgmai/ace/service/oracle/SelftestIT.java`) is the two-sided gate:
`positiveControlPassesEveryRequiredCheck` asserts the known-good fixture scores ≥95% on every
required id, and six cheat tests assert each adversarial fixture stays below 70% and/or trips the
specific check it targets. Its own javadoc documents the one deliberate adaptation from the original
design: a JUnit `@Tag("docker")` test class instead of a CLI `--selftest` flag, and a full-oracle
single pass instead of the original's offline-then-docker two-pass. There is no CI configured in
this repository (no `.github/workflows`), so `SelftestIT` and every other Docker-gated test run only
when a developer runs them locally (`./gradlew test` includes them whenever Docker is up) — there is
no automated gate re-running them on every change.

This port also has its own **adversarial code review**: `review.txt` and `review_fixes.txt` in
`ace-service/` record 66 numbered findings (bugs, resource leaks, race conditions, NPEs, dead code)
against this Java implementation, each with a fix logged and `./gradlew test` re-verified green —
visible in the git history as a long run of `fix:`/`chore:` commits each citing a review number. That
is real, substantial hardening of the harness's own correctness, on top of (not instead of) the
ported positive-control/cheat suite above.

## 7. The runner: isolation, provenance, budgets, validity

`runner/RunBench.java` is the port of `run_bench.py`, and the core mechanics check out against the
code:

- **Both harness modes exist**: `monolithicPhases()` (one growing session, structural compaction at a
  configured trigger) and `orchestratedPhase()` (stable pack + per-task packs), selected by the
  `mode` config value.
- **Docker lifecycle**: ACE does not start or stop Docker Desktop itself — the operator owns that
  entirely. `docker/DockerService.java` only probes whether it happens to be running
  (`dockerRunning()`) and mirrors the agent's Docker CLI plugins into its fresh HOME (`dockerTools()`);
  Docker-gated oracle checks (`RunOracle`, `BuildChecks`, `ComposeChecks`) already auto-detect
  unavailability and skip gracefully, independent of this.
- **A fixed integration task** exists at the end of orchestrated runs (`Packs.INTEGRATION_INSTRUCTION`
  + a dedicated `INTEGRATION` task pack), matching the original's design.
- **Validity** (`runner/Validity.java`) is a close, verified port: a run is invalid (recorded, never
  ranked) on a non-budget agent crash, a `length` finish with no artifact, a dead proxy, an error rate
  above `maxErrorRate`, zero LLM requests recorded, a request referencing another run's workspace, an
  unparseable plan, or (in orchestrated mode) the integration task never running. A budget exhaustion
  is explicitly treated as a **result**, not an invalidation — this is the same "censor both arms the
  same way" rule from the original's design rules (§18).
- **Provenance and snapshots**: `RunBenchSupport.java` provides the scrubbed environment (`ACE_RUN_ID`
  set into the agent's process env — see the note below), git snapshotting after each phase/task, and
  the single-run lock. One deliberately-unrenamed detail: the run lock lives at
  `~/.cache/agentbench/run.lock`, the *same path* the sibling Python service uses, so the two
  implementations serialize against each other rather than racing for the same model server.
- **Two selectable harnesses, not one — `agent/ReferenceAgent.java` and `agent/PiAgent.java`.** The
  original project's later design added its own non-Pi reference agent (`runner/agent_loop.py`,
  `ref-1.0`) specifically to control reasoning effort, compaction behaviour, and loop version
  alongside Pi, not instead of it — and this port carries that same choice forward rather than
  retiring Pi: `RunSpec.HARNESSES` is `{"ref", "pi"}`, both constructor-injected into `RunBench`,
  selected per run via `--harness=`. `agent/ReferenceAgent.java` (`AGENT_VERSION = "jls-ref-1.0"`) is
  the one the rest of this document covers in detail — see §8.

## 8. The agent — ReferenceAgent and PiAgent

Two harnesses are selectable per run (`RunSpec.harness`, `"ref"` or `"pi"` — see §7):

- **`agent/PiAgent.java`** drives an external agent subprocess rather than calling the model
  in-process: it owns that subprocess's own lifecycle (start, HOME seeding, process-tree kill on
  stop) and folds its output back into the same journal/session machinery everything else here uses.
  This document does not re-derive Pi's own internal loop (it is not this project's own loop to
  describe) — only that it exists here as a real, selectable alternative, not a retired one.
- **`agent/ReferenceAgent.java`** is a direct, verified port of `runner/agent_loop.py`, built on
  LangChain4j against an OpenAI-compatible endpoint, and is the one the rest of this section covers:

- **Six tools, not four plus one.** `read` (line ranges), `write`, `edit` (exact-match replace,
  with `replace_all`), `bash` (zsh, timeout parameter, default/max caps) are the four core tools;
  `recall_tool_output` (#424 — recalls an earlier tool call's original output from the session's own
  journal once it's been dropped by compaction, rather than re-running a command that might itself be
  expensive) and `record_lesson` (#332 — lets the agent itself report a cross-cutting, non-task-
  specific finding; see §10) are each an independently toggleable addition, defined via LangChain4j
  `ToolSpecification`s in `toolSpecs()`.
- **Same loop shape**: a turn with no tool call ends the session; the proxy's 429 (budget exhausted)
  ends the session with `rc=3` via a dedicated `BudgetExhausted` exception, matched against a literal
  string (`RecordingProxy`'s own error body) rather than by status code alone, since a real upstream
  429 and the proxy's budget refusal are otherwise indistinguishable over HTTP; transient 5xx / typed
  LangChain4j exceptions are retried up to 4 times with exponential backoff; a hard 400-turn cap
  exists as a backstop (exiting `rc=1`, not a false success, if the model never stops on its own).
- **Pluggable compaction strategies (#417/#421/#426), not a single fixed mechanism.**
  `CompactionStrategies.apply()` runs whichever strategies a run/task has enabled, in the configured
  order, once the last prompt exceeds `props.compactionTrigger()` (job/task-overridable via
  `compaction_strategies`). `CompactionStrategies.KNOWN` is four mechanisms; `DEFAULT` enables three
  of them (`structured_eviction` stays opt-in):
    - `tool_result_mask` (**on by default**) — the original design's own "stub the oldest tool
      outputs, keep the newest turns, never touch the system prompt" approach, now token-budgeted
      rather than turn-count-only (the protected window itself is capped so it can never grow to
      consume the whole budget). Found live (#325): it now also stubs a `write` call's own
      full-file argument once its result is masked, not just the result itself — that argument is
      often the larger of the two and was previously never compacted at all.
    - `error_line_extraction` (**on by default**, #423) — when a `bash` output's own head/tail cap
      would otherwise let its omitted middle go completely dark, scans it for FAILED/Exception/
      assertion-diff/stack-frame lines and keeps a capped number of the newest matches verbatim.
    - `tool_output_recall` (**on by default**, #424) — the toggle that makes `recall_tool_output`
      (above) a real behavioural change rather than just hiding the tool: disabled, a masked
      result's own stub text falls back to "re-run the command if you need it" instead of pointing
      at a tool the model cannot actually call.
    - `structured_eviction` (**opt-in**) — a one-shot, fixed-outline LLM summary of the evicted
      range (LANDED/STATE/INVARIANTS/TASKS/DO NEXT/DECISIONS), updated in place on later passes, the
      same pattern this project's own inter-task handoff-notes/`lessons.md` already use, applied
      within one session. Its own one-shot LLM call is capped and a failed call is logged (#426)
      rather than risking an unbounded request or vanishing silently; it also spends part of the
      task's own measured token budget, journaled separately from ordinary turns so that cost stays
      visible rather than hidden inside a side call nothing accounts for.

  A different layer — KV-cache-level eviction (H2O/StreamingLLM/PyramidKV) inside oMLX's own
  attention mechanism - is deliberately out of scope here: it isn't anything this codebase
  controls (oMLX is a third-party app), only something to request/configure on oMLX's side. oMLX's
  underlying `mlx-lm` already ships `--kv-bits`/`--kv-group-size`/`--quantized-kv-start` (quantized
  KV, disables request batching while active - and one current report shows `--kv-bits` silently
  corrupting prefilled context on some builds, in-context recall failing while fp16 is correct) and
  `--max-kv-size` (a rotating, fixed-size cache, StreamingLLM-shaped) as concrete settings worth
  evaluating if this project ever upgrades to use them - with one real caution first: "The Pitfalls
  of KV Cache Compression" (arXiv 2510.00231, 2025; rev. 2026) found that under KV compression, some
  instructions in a multi-instruction PROMPT (not only a system prompt - that's the paper's own
  worked case study) degrade far faster than others and become effectively ignored, with observed
  system-prompt leakage and reduced instruction-following, naming three contributing factors:
  compression method, instruction ORDER, and eviction bias. The middle one is a lever this project
  already controls - `structured_eviction`'s own one-shot request used to prepend its instruction
  as a SECOND system message ahead of the agent's real one; it now appends it last, closest to
  generation, as a plain user message (#417 follow-up review) - so any future KV-compression
  evaluation should check instruction-following under the specific chosen technique, not just
  overall task completion. See issue #425 for the full research trail.
- **`reasoning_effort` transmission and read-back are both verified working.** The original design
  records `reasoning_effort` as a *treatment* sent on every request and read back from the journal
  for provenance. This port sends it via the standard OpenAI field
  (`OpenAiChatRequestParameters.builder().reasoningEffort(...)`, not the original's oMLX-specific
  `chat_template_kwargs.reasoning_effort`) — `DEFAULT_REASONING` picks the per-session-kind value and
  `ReferenceAgent`'s chat-model builder sets it on every request. `JournalFacts.java` reads it back
  from that same top-level field for the provenance histogram, matching what is actually sent.

## 9. Judge — mid-task trajectory checkpointing

Not part of the original Python design at all — a capability this port grew natively. `runner/Judge.java`
is a separate LLM session, run every N agent turns within a task (`judge_every_n_turns`, default 10),
that reads the task's own goal, budget, diff-so-far and recent trajectory and replies with exactly one
verdict line, `ON TRACK` or `OFF TRACK` (parsed case-insensitively from the first line of its reply),
plus guidance injected as the agent's next turn when off-track. A run of consecutive off-track
verdicts tightens the judge's own check-in cadence (a drifting task gets checked more often, not left
to the original interval) and, near budget exhaustion, switches into a "triage mode" notice rather
than its ordinary guidance. Every checkpoint's own reply is written to
`judge/<taskId>-<attempt>-turn<N>.md` for later inspection (the per-task Judge tab in the UI, §14,
reads these). Task-scoped only — p0/p1/PARALLEL_PLAN/wrap-up/handoff/review sessions never run it.
`judge_enabled` defaults to **on** for every run (`RunSpec.argv()` always emits either `--judge` or
`--no-judge` explicitly, so an operator's deliberate opt-out survives the round trip); it is also one
of the few knobs a paused job's per-task Restart tab can retroactively enable.

## 10. Cross-task knowledge sharing — lessons.md

Also not part of the original design. The handoff mechanism (the per-task packs §7/§13 describe)
only ever propagates a task's own notes along its OWN dependency edges, so a host-level quirk or
discovered constraint one task finds has no channel to reach a LATER, unrelated task that never
depended on it — every task was independently rediscovering the same facts. `record_lesson(text)`
(#332) is a tool offered to the main task session, a parallel-wave task session, and both the fast
in-session and cold timed-out-task handoff paths, letting the agent itself report a cross-cutting
finding mid-task. `RunBench.recordLesson()` is the one canonical write path — judge's own off-track
recommendations and a budget-rescue reconnect's own reason are ALSO recorded through it, not a
separate mechanism — into a single run-wide `lessons.md`, which `readLessons()` folds into every
downstream task's own pack (`Packs.taskPack()`) and the final `INTEGRATION` task's pack alike,
independent of the dependency graph. Since this file is a shared, run-wide resource potentially
written by a dozen or more tasks, its size is capped (`lessons_max_chars`, default 6000, 0 =
unlimited): crossing that ceiling triggers a deterministic, zero-LLM tier-1 compaction pass that
dedups near-identical entries, keeping the MOST RECENT of any duplicate group. An LLM-based tier-2
compaction (for when dedup alone isn't enough) is a known, deliberately deferred gap, not yet built,
because an LLM pass risks corrupting the exact kind of factual record this feature exists to make
trustworthy. `lesson_recording_enabled` is its own dedicated toggle — not a `CompactionStrategies`
member, despite briefly living there during this feature's own first implementation, found not worth
the conceptual mixing it caused — defaulting to **on**, mirroring `judge_enabled`'s exact pattern
(`RunSpec.argv()` always emits `--lesson-recording` or `--no-lesson-recording`).

## 11. Operator intervention and pause-after-task

Neither is in the original design. `runner/Intervene.java` is a plain in-memory mailbox, keyed by
(runId, taskId), that lets an operator inject a live steering message into a running task as its next
instruction — delivered through the same per-turn check Judge already uses, but with no LLM call and
no run-level enablement gate; an operator can intervene on any task at any time regardless of that
task's own `judge_enabled`. Not persisted (a live command, not state meant to survive an
`ace-service` restart), but delivery itself IS durably logged: the injected text lands in the task's
own session JSONL via the same `session.user(...)` call Judge's own recommendations already use,
searchable later via `grep -rl "Operator intervention" results/<run>/sessions/`. `runner/PauseAfterTask.java`
is a graceful stop request for a RUNNING job — unlike the existing `/pause` endpoint's immediate
interrupt, which can discard a task's own in-progress turns, this waits for the task currently running
to actually finish, then calls the exact same `JobQueue.pause()` the immediate path already uses, just
at a safer, between-tasks checkpoint. Both are exposed on the Live tab (§14).

## 12. Recording and metrics

`proxy/RecordingProxy.java` is the port of `record_proxy.py`: it sits between the agent and the model
server and journals requests/responses; `SseAssembler.java` handles the streamed-response
reassembly. `metrics/Collect.java`, `metrics/Trajectory.java` and `metrics/StatsService.java` port
`collect.py`/`trajectory.py`/`stats.py` respectively. `StatsService` was read directly and confirms
the core statistical machinery: `bootCi()` (bootstrap confidence intervals), `permutationTest()`, and
`requireMatchedBudgets()` — the same "harness-effect comparisons refuse unless budgets match" rule
from the original. `MODEL_AB_EXEMPT` (the setup-dependent fields exempted from a model A/B's
comparability key — window, derived knobs, budgets, system-prompt SHA) is also present and correctly
named (this is an "A/B" as in *model* A/B testing, unrelated to this project's own "AB → ACE" rename;
it was deliberately left alone during that rename for exactly that reason).

## 13. Harness modes

Both modes described in the original design are present in `RunBench.java`:

- **Monolithic**: one growing session per phase, with structural compaction triggered by a token
  threshold as context accretes.
- **Orchestrated**: task-by-task, each with a composed context — a stable pack in the system prompt
  (`Packs.stablePack()`), a deterministic per-task pack (`Packs.taskPack()`, capturing the task entry,
  dependency interfaces, repo tree, previous task's diff, harness-run test verification, and progress
  notes), a wrap-up stop protocol, and a fixed integration task at the end. The class-level Javadoc on
  `RunBench` additionally claims **parallel waves** are supported (`Packs.parallelPlanPack()` exists,
  and `RunBench` has merge/conflict-handling code for parallel task waves feeding into the integration
  task) — this parallel-orchestration capability was part of the original project's later work and
  appears to have been carried into this port, though its correctness under load was not
  independently re-verified here.

This design document does not restate the original's specific historical benchmark numbers (see §15)
since those were produced by the Python implementation on specific models and are not this port's own
results.

## 14. The Vaadin front end

Reduced in earlier drafts of this document to one line in §2's directory tree — `ace-ui-vaadin` is a
substantial module, ~8,400 LOC across 14 routed views: `RunsView`/`RunDetailView` (imported run
results), `JobsView`/`JobNewView`/`JobDetailView` (the live job queue — creation, per-task plan/status
grid, and Prompt/Changes/Restart/History/Judge/Stats/Live tabs per task), `ExperimentsView`/
`ExperimentNewView`/`ExperimentDetailView` (multi-arm A/B experiments), `GroupsView`, `CompareView`
(cross-run comparison), `PreflightView`, `FileViewerView`, `DiffView`, and `RootView`. Live state on
the Jobs/Job-detail pages is SSE-driven, not polling alone: `JobEventLoop.java` and
`TaskTranscriptLoop.java` stream a running job's own events and a task's live transcript respectively.
`CompareView` does not do a naive id-for-id task match between two runs — `runner/TaskMatcher.java` is
an LLM-based matcher (#339) that semantically aligns two independently-decomposed
`--plan-source=agent` plans (same goal, different task ids/granularity) before the UI can compare
them task-by-task at all. The operator-intervention controls (§11) live on the Job-detail page's Live
tab.

## 15. Results so far

This port has since produced a substantial number of recorded benchmark runs — `ace-service/results/`
holds dozens of them as of this revision, spanning model A/B pairs (`ab-*`), direct named
service-build runs (`svc-*`), and task-granularity-ladder experiments (`l2-gran-*`), with
manifest/oracle/metrics/trajectory output written by `RunBench` exactly as designed. Exact run
counts and scores are not restated here — unlike §3's fixed, versioned scoring design, this is live,
continuously-growing state, queryable via the UI's Runs page or `GET /api/runs`, not a fixed
historical table worth freezing into a design document. What was true when this section was first
written, and remains real, durable evidence of this port's own correctness work independent of any
one live run:

- **66 code-review findings, all fixed** (`review.txt`, `review_fixes.txt`), spanning bugs, resource
  leaks, race conditions, and dead code across the agent/runner/oracle/service packages, each closed
  with a cited commit.
- **139+ unit and integration tests passing** (`./gradlew test`), covering the oracle checks, the
  scorer, plan parsing, the recording proxy, journal-fact extraction, and the service layer (job
  queue, worker, experiments, importer) against a stub server and a fresh, real SQLite database per
  test (Flyway-migrated, queried through jOOQ's `DSLContext`) — `ace-service`'s own operational store
  moved from Postgres to SQLite partway through this port's development, so no external DB server or
  Testcontainers container backs these anymore. `./gradlew dockerTest` additionally builds this
  service's own Dockerfile into a real image (Testcontainers' `GenericContainer`/`ImageFromDockerfile`
  — still a real dependency, just for this, not for any database) and drives it end-to-end (git
  preflight, JDK pin, a real HTTP round trip) — verified passing against a live Docker daemon during
  this port's development.
- **Functional/composite scores against live local/hosted models now exist**, recorded in `results/`
  by real runs — but, per the "live state" note above, this document does not restate them inline;
  read a run's own manifest/oracle output directly, or the UI's Run-detail page, rather than trusting
  a number frozen into this document at some earlier point in time.

## 16. Known limitations

Carried over from the original design, and still true of this port as far as could be verified:

- The agent is not sandboxed: it runs as the operator's OS user with network access on the host.
- Compose runs on the host Docker daemon (no rootless/DinD).
- The gaming-resistance fixture suite (positive control + adversarial cheats + selftest) described in
  the original design **is present** in this port (§6) — but with no CI configured in this
  repository, nothing re-runs it automatically; it only runs when a developer runs the test suite
  locally with Docker up.
- Per-step scoring, isolated-step measurement, and the review-calibration tooling described in the
  original design's later sections were not exhaustively re-verified against this port's code beyond
  confirming that `Packs.java` carries `reviewPack`/`trajectoryReviewPack`/`fixPack` methods; treat
  their exact behaviour as unconfirmed until read directly.

## 17. How to run

```bash
# from the repo root (agentic-coding-evaluation/) — ace-service is a Gradle subproject
./gradlew :ace-service:test                 # unit + stub-server + fresh-SQLite integration tests
./gradlew :ace-service:dockerTest           # builds this service's own Dockerfile into a real image and
                                             # drives it end-to-end (needs a running Docker daemon)
./gradlew :ace-service:bootRun              # run the service directly
```

The original's Python CLI entry points (`runner/run_bench.py --task ... --mode orchestrated ...`,
`runner/harness-effect.sh`, `runner/model-ab.sh`, `oracle/run_oracle.py --selftest`) do not have a
verified 1:1 Gradle-task equivalent in this port as of this document — `RunSpec.java` builds a
`--harness=` style argument list suggesting an analogous CLI/queue-driven run path exists via the
service's job queue (`service/JobQueue.java`, `service/WorkerService.java`) and the Vaadin UI's job
pages (§14), but the exact command surface was not enumerated here.

## 18. Design rules distilled

These are the original project's design rules. They are methodology, not code, and remain the
standard this port is held to regardless of language:

1. Fix the denominator; absence is failure; a crash can never raise a score; partial runs are never quoted.
2. The only correctness evidence is an oracle-owned test against the *running* system; self-authored green is not evidence; mutation-test the suite; freeze the contract or there is nothing to test against.
3. Build a positive control before trusting a negative gate: it finds the oracle's own bugs. (Built in this port — see §6.)
4. Separate infrastructure failure from agent failure, and never charge the machine to the model.
5. Pin what you claim: sampler parameters via the proxy, packages by version, prompts by hash; record what was *sent*, not what was configured.
6. Kill process trees, mark run processes in the environment, never read a child's stdout through a pipe its grandchildren can inherit.
7. Compose the agent's context; do not let it accrete. Stable prefix first, per-task pack next, reachable stop conditions, a harness-owned status trail.
8. One run is one draw: k ≥ 5, CIs, pass^k, and never pool runs whose harness, budgets, model, oracle or contract differ.
9. Every state-changing scenario must assert the intermediate state, or a hollow server passes; a mutation test needs a green suite, or a red suite "catches" everything.
10. Match total budgets before claiming a harness effect; a session that died on a stop-policy artefact is not an observation.
11. Trust harness-verified completion, never the model's own "done".
12. A self-review is a self-report: score it by calibration against an oracle or an objective index, never add it to the correctness score; an independent reviewer's judgment may count directly, with its identity on the record.
13. Build the runtime under test from a clean checkout — anything the agent left in its working tree is not the code — and keep build outputs out of the snapshots too.
14. Pin the toolchain the agent and the harness share (JDK, Gradle) and prove it with a real smoke test before the clock starts; a verification that could not run is inconclusive, never the agent's RED.
15. Censor both arms of a comparison the same way; the runner is part of the treatment, so its hash is part of the comparability key; a matched budget is checked numerically, not assumed.
16. Score every step, not just the end state — but attribute honestly and measure a step in isolation from a fixed prior state before choosing a model for it.
17. Measure the context window the setup actually offers before anything else, and derive every context-dependent knob from that measurement, never from a number typed into a config.
18. When the grader's contract changes, the specification the agent reads changes in the same commit — and the positive control must be able to fail the new rule, or add a fixture that does.
