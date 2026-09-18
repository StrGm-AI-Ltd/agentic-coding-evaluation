#!/bin/zsh
# agentbench: Docker Desktop ON DEMAND. Installed as `docker` first on the agent's PATH. The first call starts Docker
# Desktop and waits for the daemon (the harness keeps it DOWN until then and stops it again after the packaging work:
# its VM competes with the model for memory and CPU while images build); every call is logged for the harness's
# window record; then the real docker CLI runs with the same arguments.
log=${AB_DOCKER_LOG:-/dev/null}; me=${0:A:h}
if [[ -n "${AB_DOCKER_SHIM:-}" ]]; then echo "agentbench docker shim: recursion (no real docker CLI found)" >&2; exit 127; fi   # never call ourselves
export AB_DOCKER_SHIM=1
real=""
for d in ${(s.:.)PATH}; do
  [[ -z "$d" || "${d:A}" == "$me" ]] && continue          # compare resolved paths: /var/... vs /private/var/... on macOS
  cand="$d/docker"    # resolve the CANDIDATE, not just its directory: if the shim is installed as
  # /usr/local/bin/docker -> /opt/agentbench/docker_shim.sh, the directory check above does not skip it
  # (dirs differ), so compare the resolved binary, or "real" would point at the shim and it would poll
  # a dead end for 180s.
  [[ -x "$cand" && "${cand:A}" != "${0:A}" ]] && { real="$d/docker"; break; }
done
[[ -z "$real" ]] && for c in /usr/local/bin/docker /opt/homebrew/bin/docker /Applications/Docker.app/Contents/Resources/bin/docker; do [[ -x "$c" ]] && { real="$c"; break; }; done
if [[ -z "$real" ]]; then echo "agentbench docker shim: no docker CLI found on PATH" >&2; exit 127; fi
print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ $*" >> "$log" 2>/dev/null
if ! "$real" info >/dev/null 2>&1; then
  # A lock so N parallel `docker` calls don't each spawn `open -a Docker` and run their own 180 s poll
  # loop: the first process does the start+wait, the rest just wait for the daemon. mkdir is atomic and
  # macOS has no flock(1). The lock is removed before exec because exit traps don't survive exec.
  lock=${AB_DOCKER_SHIM_LOCK:-/tmp/agentbench-docker-shim.lock}
  if mkdir "$lock" 2>/dev/null; then
    trap 'rmdir "$lock" 2>/dev/null' EXIT INT TERM
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #start" >> "$log" 2>/dev/null
    /usr/bin/open -a Docker 2>/dev/null
  else
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #wait (another shim instance is starting Docker)" >> "$log" 2>/dev/null
  fi
  for i in {1..90}; do
    "$real" info >/dev/null 2>&1 && break
    /bin/sleep 2
  done
  if "$real" info >/dev/null 2>&1; then
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #ready $((i*2))s" >> "$log" 2>/dev/null
    rmdir "$lock" 2>/dev/null
  else
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #failed after $((i*2))s" >> "$log" 2>/dev/null
    echo "agentbench docker shim: Docker Desktop did not become ready within $((i*2))s (no install? out of memory?)" >&2
    exit 1
  fi
fi
exec "$real" "$@"
