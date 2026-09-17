# Prior states for isolated-step runs (`--step <id> --state <state>`)

A per-step score computed at the end of a full run is attributed by responsibility (`Oracle:` labels in the plan)
and is therefore confounded by what the same model did in the earlier steps. To measure ONE step on its own -
"how good is this model/configuration at T3, given a correct T1+T2?" - the harness starts the step from a fixed
prior state and scores only the step's attributed checks:

    python3 runner/run_bench.py --task L3p_point_in_time --step T3 --state <state> --task-wall 3600 --task-tokens 80000 --model <m> --manage-docker

`<state>` is either

- a directory (a checked-out solution), or
- `<workspace.bundle>@<ref>` - a finished run's bundle at a snapshot tag, e.g.
  `results/he-20260913-0012-Qwen3827Bg-orch-r2/workspace.bundle@phase/T2` (the state after that run's T2).

The state's `docs/IMPLEMENTATION_PLAN.md` drives the step (its `Oracle:` labels decide what is scored); the
workspace is seeded without build outputs; the pack lists the interfaces of the whole tree (no dependency
snapshots exist). Runs of the same step from the same state (`state_sha`) pool in `metrics/stats.py`; a step run
never pools with a full run.

## Reference states (to be built)

Canonical states derived from the positive control (`fixtures/positive`) so that every model starts a step from
the SAME correct prior work:

| state | content | how to build |
|---|---|---|
| `after-T1/` | account-service with schema, accounts, deposits, health; no orders/holdings; **plus the positive control's Dockerfile and compose** | positive control minus the order/holdings endpoints, services, and their tests |
| `after-T2/` | + orders with immediate fill and the ledger; Dockerfile/compose present | positive control minus the holdings endpoint and its tests |
| `after-T3/` | + point-in-time holdings; Dockerfile/compose present | positive control as is (T4's own work becomes a no-op check of the packaging) |

**The runtime must be in every state.** T3 is scored on F1/F2/F8, which need the running stack that the plan only
delivers in T4; a state without Dockerfile/compose can never earn them (`--step` warns: "scored on runtime checks
but the prior state has no compose file"). Infrastructure is not the step's work, so the states carry the positive
control's multi-stage Dockerfile and compose from the start.

Each state must pass `gradle test` against PostgreSQL (`oracle/run_oracle.py <state> --task L3p_point_in_time`
with Docker: B1/B2 PASS and the attributed checks of the LATER steps FAIL/NOT_ATTEMPTED - a state that already
contains the step's work measures nothing). Until these directories exist, use a scored run's bundle at the
snapshot before the step (the table above names the tags) and record `state_sha` with the result.
