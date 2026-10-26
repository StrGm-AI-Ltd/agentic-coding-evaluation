# Deploying ACE

Runs the exact same `ace-service` + `ace-ui-vaadin` pair as `scripts/dev.sh` does locally, as two
containers on a single host, via `docker-compose.yml` at the repo root. No code changes, no new
routes, no authentication (that's a deliberately separate, later step) - this deploys ACE as-is.

Images are built by `.github/workflows/docker-publish.yml` on every push to `main` and pushed to
`ghcr.io/strgm-ai-ltd/ace-service` and `ghcr.io/strgm-ai-ltd/ace-ui-vaadin`. The deploy host never
runs the Gradle/Vaadin-frontend build itself - it only pulls the already-built images.

## Why a plain VM, not a managed container service

Two hard requirements rule out ECS/Fargate/App Runner/similar:
- `ace-service` needs the **host's own Docker socket** - `RunOracle`'s build/compose checks run
  the agent's own generated docker-compose stacks via `DockerService.java`'s direct `docker`/
  `docker compose` exec calls, which need a real daemon reachable from inside the container
  (Docker-outside-of-Docker). Managed container runtimes don't expose a usable host socket.
- Everything that should survive a redeploy (the SQLite DB, results, workspaces) needs **real
  persistent disk**, not ephemeral container storage.

A plain Linux VM (EC2 instance, or any VM with a Docker-capable kernel) with an attached disk is
the right fit.

## 1. Provision the instance

- **AMI**: Ubuntu 24.04 LTS (or any recent Ubuntu/Debian - matches the containers' own base).
- **Size**: `t3.large` (2 vCPU / 8 GB) comfortably covers this. The instance only *runs* the two
  containers (two JVMs, plus whatever a live agent run's own generated docker-compose stacks need
  via the socket mount below) - it never builds them, so sizing is a runtime concern, not a build
  one.
- **Disk**: 20+ GB (pulled images + the persistent data volume; no build cache needed here).
- **Security group**: open 22 (your own SSH access, ideally restricted to your own IP) and **80**
  only (the UI, published there via `ACE_UI_PORT` below instead of 8800 - a clean judge-facing URL
  with no port in it). `ace-service`'s own 8765 never needs to be open to the internet - the UI
  reaches it over the compose-internal network, and nothing external talks to it directly.

## 2. Install Docker on the instance

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # log out/in (or newgrp docker) for this to take effect
```

## 3. Fetch the compose file and configure

The deploy host only ever pulls pre-built images from `ghcr.io` - it never reads the app's own
source, so a full `git clone` isn't needed. `docker-compose.yml` (plus the `.env` you create below)
is the only file this box requires:

```bash
mkdir agentic-coding-evaluation && cd agentic-coding-evaluation
curl -fsSL -O https://raw.githubusercontent.com/StrGm-AI-Ltd/agentic-coding-evaluation/main/docker-compose.yml
stat -c '%g' /var/run/docker.sock   # note this number - it's your DOCKER_GID
cat > .env <<EOF
DOCKER_GID=<the number from above>
ACE_UI_PORT=80
EOF
```

`OMLX_API_KEY`/`ACE_ENDPOINT` are deliberately left unset - there's no oMLX server reachable from
this box, and nothing on this deployment needs one (the LLM Providers registry, configured in the
next step, is what any job actually runs against).

## 4. Pull and start

```bash
docker compose pull
docker compose up -d
```

**One-time step after the very first push to `main`**: GitHub Container Registry packages are
private by default on first publish, regardless of the repo's own visibility - a plain
`docker compose pull` with no credentials on this box will fail until that's changed. In the
repo's GitHub page, open the **Packages** sidebar entry for each of `ace-service` and
`ace-ui-vaadin`, then *Package settings* → *Change visibility* → **Public** (optionally also
*Link to repository* so it inherits this going forward). Only needed once, ever.

## 5. Configure a provider

Open `http://<instance-public-ip>/providers` and add (or edit, if it's one of the five
seeded rows) Nebius's own API key - the DB is fresh on a new volume, so this doesn't carry over
from any local setup. Confirm it resolves live models: pick Nebius in any model-provider dropdown
(e.g. on `/jobs/new`) and confirm the model combo populates.

## 6. Smoke test

- UI loads at `/`. The generic Preflight tab (`/preflight`, no model selected) will show the
  model-server check as unavailable - **expected and harmless**, this box has no local model
  server. Preflight checks a *job's own* `--model` against whichever provider it's prefixed with
  (falling back to the local default only for a bare model name), so this generic view staying
  "unavailable" does not block anything targeting a configured provider.
- Start a small real job targeting `nebius/<model>` through the standard "New job" form. Confirm
  it runs to completion and scores - this is also what proves the model-server check actually
  passed for that job (a blocked Preflight report refuses the job outright before it starts).
- In that run's own results, confirm the B1 build check actually ran (status `PASS`/`FAIL`, not
  `SKIPPED "docker unavailable"`) - that's the Docker-outside-of-Docker wiring (the socket mount +
  `DOCKER_GID`) working correctly. If it still says "docker unavailable," re-check step 3's GID.
- Persistence check: `docker compose down && docker compose up -d`, confirm the run and the
  Nebius provider row are both still there.

## Redeploying

```bash
docker compose pull
docker compose up -d
```

The application images always come from whatever's newest on `ghcr.io` (pushed automatically on
every push to `main`) - just re-running the above picks up the latest. Only re-fetch
`docker-compose.yml` itself (step 3's `curl`) if you know that file specifically changed. The
`ace-data` volume (DB, results, workspaces) is untouched by any of this.
