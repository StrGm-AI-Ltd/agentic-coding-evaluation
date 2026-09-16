# Harness fixes to port into the Java rewrite

Latest fixes made in the Python harness (`~/Documents/repo/agentbench-trading-service`, branch `service`) that this Java rewrite should mirror.

Source commits:
- `c9340b5` — Docker fallback + Spring integration-test recipe
- `1b086e3` — discriminating-tests rule + oracle M4 fix
- `a8229ec` (earlier) — SYSTEM "bias to action" (include if not already ported)

**Two layers.** **A) the pack** = the text assembled into each task's prompt (Fixes 1–4 are pure prompt text — copy the strings into whatever your Java code uses to build the prompt). **B) the oracle** = the automated scorer (Fix 5 is scoring logic).

---

## Fix 1 — Bounded Docker retries + Docker-less fallback (prompt text)

**Problem:** told "Docker is available on demand," the agent burns ~50 min trying to start a Docker daemon that cannot boot on a RAM-constrained host (the model wires most of the RAM). Wasted turns; it never needed to *run* the container — a correct Dockerfile/compose is enough because the grader runs containers later.

**Where:** the "Docker" note block appended to packaging/integration tasks, and the integration task instruction.

**Change — append to the Docker note** (verbatim, agent-facing):
> If a `docker` command does not succeed within ~60s the daemon is unavailable on this host (it competes with the model for memory): do NOT keep retrying, run `open -a Docker`, or spawn your own backend - write the Dockerfile/compose.yml from your own knowledge, verify behaviour against the provided SPRING_DATASOURCE_*/H2 datasource, and rely on the grader (it builds and runs the containers on a clean checkout after your session).

**Change — in the integration instruction**, replace *"work (Docker starts on demand - see the Docker section; the grader…"* with:
> …work when Docker is available (it starts on demand but may not boot on this host - see the Docker section's ~60s fallback and do not get stuck retrying; the grader will run `docker compose build` on a clean checkout…

**Java notes:** pure text. Keep the fallback even if the Java host has RAM headroom during the agent phase — it's harmless and prevents the loop. `open -a Docker` is macOS-specific in the example; the directive still reads fine cross-platform.

---

## Fix 2 — Canonical Spring integration-test recipe (prompt text)

**Problem:** the agent thrashed ~50 min on Spring Boot test wiring (`TestRestTemplate` vs `spring-boot-test-autoconfigure` vs `setRootUri` across Boot versions).

**Where:** the "working rules" / hygiene block injected into every task prompt — the Spring line.

**Change — extend the Spring rule to** (agent-facing text):
> Spring: slice tests over `@SpringBootTest` where a DB is not needed; for a full HTTP integration test use `@SpringBootTest(webEnvironment=RANDOM_PORT)` with an injected `TestRestTemplate` (already on the test classpath via `spring-boot-starter-test`) or a plain `RestTemplate` to `http://localhost:${port}` - do NOT remove `spring-boot-starter-test` or its autoconfigure.

**Java note — one gotcha:** in the committed Python this appears as `http://localhost:${{port}}` because that template runs through Python's `str.format(window=…)`, which treats `{…}` as a field, so the literal brace is doubled. **The agent must see `${port}` (single braces).** In your Java templating, emit `${port}` and escape only if your own templater treats `{}` / `${}` specially.

---

## Fix 3 — Discriminating-tests rule (prompt text)

**Problem:** happy-path tests miss silent framework-binding bugs — e.g. a missing `@RequestBody` makes a POSTed `currency` silently ignored and default to USD; a test that only posts the default never catches it.

**Where:** the per-task instruction that tells the agent to write tests.

**Change — insert after "Write tests that prove its acceptance criterion and run them"**:
> ; your tests must use DISCRIMINATING inputs - non-default values a hardcoded or ignored-input implementation would get wrong (e.g., POST a non-USD currency and assert THAT currency echoes back, not the default; assert a non-zero, non-default quantity round-trips) - happy-path defaults prove nothing.

**Java notes:** pure text.

---

## Fix 4 — SYSTEM "bias to action" (prompt text; earlier — include if missing)

**Problem:** at high reasoning the agent over-researched (e.g. fetching package registries to pin exact versions) and wrote no code.

**Where:** the coding agent's base SYSTEM prompt.

**Change — append**:
> Bias toward action: the build and tests are your feedback loop, so prefer writing a first version and running it over researching to eliminate uncertainty up front - gather only what you need for the next concrete step, and once you can write a file, write it. Use conventional, known-good versions of tools and dependencies from your own knowledge; do NOT spend turns fetching remote metadata (package registries, plugin portals) to pin exact versions - pick a reasonable recent version and let the build tell you if it is wrong.

---

## Fix 5 — Oracle M4: SQL net-holdings false-negative + wrong-file scan bug (scoring logic)

The only **logic** change. M4 credits a point-in-time holdings replay that *subtracts sells* (including SQL implementations). Two problems:

**Bug (a):** when collecting source files that contain a `@Query` / `createQuery` / `nativeQuery`, the scan read the **wrong file** — a stale loop variable rather than each candidate — so SQL-in-Java detection was effectively broken.

**False-negative (b):** the SQL regex only matched `CASE WHEN … SELL … -qty`; it missed the sign-via-ELSE form (`CASE WHEN BUY THEN qty ELSE -qty`).

**Fixed Python (for reference):**
```python
# credit a signed CASE on the sell side, or the sign carried via ELSE
sql_subtracts = re.compile(
    r"CASE\s+WHEN[^)]*(SELL|SOLD)[^)]*(-\s*\w*(qty|quantity)|\* ?-1)"
    r"|CASE\s+WHEN[^)]*(BUY|BOUGHT)[^)]*ELSE\s*-\s*\w*(qty|quantity)",
    re.I | re.S)
for q in sql + [j for j in java if re.search(r"@Query|createQuery|nativeQuery", read(j))]:
    if sql_subtracts.search(read(q)):   # read EACH candidate q, not a stale variable
        pit_methods += 1
```

**Java-ready pattern** (`Pattern.CASE_INSENSITIVE | Pattern.DOTALL`, or inline `(?is)`):
```
(?is)CASE\s+WHEN[^)]*(SELL|SOLD)[^)]*(-\s*\w*(qty|quantity)|\* ?-1)|CASE\s+WHEN[^)]*(BUY|BOUGHT)[^)]*ELSE\s*-\s*\w*(qty|quantity)
```

**Java notes / invariants to preserve:**
- **Scan each candidate file's own content** (this is the bug fix — verify your loop reads the file it is currently testing, not a shared/last reference).
- Candidate set = SQL files **plus** Java/Kotlin files whose text contains `@Query`, `createQuery`, or `nativeQuery`.
- **Keep it conservative.** Deliberately do *not* match a buys-minus-sells of two aliased `SUM()`s (e.g. `SUM(t.qty) - SUM(s.qty)` distinguished only by join alias) — it cannot be detected from source without false-positives, and over-broadening a *scoring* regex silently over-credits. That case is meant to be covered by the behavioural mutation test (B3).
- Validated to **reject** look-alikes: plain `SUM(quantity)`, buys-only `CASE … ELSE 0`, and fee-netting `SUM(buyFee) - SUM(sellFee)`.

---

## Not ported (still open in the Python repo — do NOT port yet)
- **Oracle B3 (mutation test) for SQL net-holdings** — B3 mutates *Java* arithmetic and cannot yet mutate a `@Query` string, so a pure-SQL implementation scores `NOT_ATTEMPTED`. Fixing it safely needs a positive+negative fixture pair; if your Java oracle has the same mutation-testing check, it inherits the same limitation.
- **Contention detector** — the managed Docker-on-demand window wrongly flags parallel runs `CONTENDED` (excluded from `stats --compare`). Needs tracing of how the Docker windows are recorded and how the compare step excludes runs.
