# Agentic Coding Evaluation (ACE)

A reproducible framework for evaluating AI coding agents, models, and agent harnesses under controlled conditions.

## Subprojects

- **`ace-service`** — the Java 21 / Spring Boot harness: scoring oracle, orchestrated/monolithic
  runner, recording proxy, and reference coding agent.
- **`ace-ui-vaadin`** — a Vaadin 25 web UI over the harness's run/job/experiment API.

Both are Gradle subprojects of this repo's root build (`./gradlew :ace-service:...`,
`./gradlew :ace-ui-vaadin:...`).

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design: what is measured, how scoring and the
runner work, and how this port compares to the original Python implementation it's based on.

## Requirements

Running an actual benchmark (the worker executing a queued job) currently only works on **macOS**:

- `Preflight`'s JDK auto-discovery falls back to `/usr/libexec/java_home -v 21`, a macOS-only tool.
  Set `ace.java-home` (or `$ACE_JAVA_HOME`) to sidestep this on any platform.
- `DockerService` manages Docker Desktop's on-demand lifecycle via `open -a Docker`, `osascript`,
  and matching the `com.docker.backend` process name — none of which exist on Linux or Windows, and
  there is currently no non-macOS equivalent.

The Vaadin UI (`ace-ui-vaadin`) and an `ace-service` instance used only to browse, import, or compare
already-produced results have no such constraint.
