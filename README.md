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
