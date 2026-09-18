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
  if [[ -x "$d/docker" && "${d:A}/docker" != "${0:A}" ]]; then real="$d/docker"; break; fi
done
[[ -z "$real" ]] && for c in /usr/local/bin/docker /opt/homebrew/bin/docker /Applications/Docker.app/Contents/Resources/bin/docker; do [[ -x "$c" ]] && { real="$c"; break; }; done
if [[ -z "$real" ]]; then echo "agentbench docker shim: no docker CLI found on PATH" >&2; exit 127; fi
print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ $*" >> "$log" 2>/dev/null
if ! "$real" info >/dev/null 2>&1; then
  print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #start" >> "$log" 2>/dev/null
  /usr/bin/open -a Docker 2>/dev/null
  for i in {1..90}; do
    "$real" info >/dev/null 2>&1 && break
    /bin/sleep 2
  done
  if "$real" info >/dev/null 2>&1; then
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #ready $((i*2))s" >> "$log" 2>/dev/null
  else
    print -r -- "$(/bin/date -u +%Y-%m-%dT%H:%M:%SZ) $$ #failed after $((i*2))s" >> "$log" 2>/dev/null
    echo "agentbench docker shim: Docker Desktop did not become ready within $((i*2))s (no install? out of memory?)" >&2
    exit 1
  fi
fi
exec "$real" "$@"
