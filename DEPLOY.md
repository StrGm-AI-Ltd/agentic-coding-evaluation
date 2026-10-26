# Deploying ACE

Runs the exact same `ace-service` + `ace-ui-vaadin` pair as `scripts/dev.sh` does locally, as two
containers on a single host, via `docker-compose.yml` at the repo root. No code changes, no new
routes, no authentication (that's a deliberately separate, later step) - this deploys ACE as-is.

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
- **Size**: at least 2 vCPU / 8 GB RAM. The images are built *inside* the instance
  (`docker compose up --build` runs the full Gradle + Vaadin-frontend build) - undersizing this
  makes the first build slow or OOM; there's no separate build-and-push step to avoid it with.
- **Disk**: 30+ GB (Docker images, build caches, and the persistent data volume all land here).
- **Security group**: open 22 (your own SSH access) and **8800** only (the UI). `ace-service`'s
  own 8765 never needs to be open to the internet - the UI reaches it over the compose-internal
  network, and nothing external talks to it directly.

## 2. Install Docker on the instance

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker "$USER"   # log out/in (or newgrp docker) for this to take effect
```

## 3. Clone the repo and configure

```bash
git clone https://github.com/StrGm-AI-Ltd/agentic-coding-evaluation.git
cd agentic-coding-evaluation
stat -c '%g' /var/run/docker.sock   # note this number - it's your DOCKER_GID
cat > .env <<EOF
DOCKER_GID=<the number from above>
EOF
```

`OMLX_API_KEY`/`ACE_ENDPOINT` are deliberately left unset - there's no oMLX server reachable from
this box, and nothing on this deployment needs one (the LLM Providers registry, configured in the
next step, is what any job actually runs against).

## 4. Build and start

```bash
docker compose up -d --build
```

First build takes a while (full Gradle build + npm/webpack frontend bundling, inside the image,
with no layer cache yet). Subsequent rebuilds (after `git pull`) are much faster - only the
`src`-copy layer and anything after it re-runs when just application code changed.

## 5. Configure a provider

Open `http://<instance-public-ip>:8800/providers` and add (or edit, if it's one of the five
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
git pull
docker compose up -d --build
```

The `ace-data` volume (DB, results, workspaces) is untouched by this - only the application
images get rebuilt.
