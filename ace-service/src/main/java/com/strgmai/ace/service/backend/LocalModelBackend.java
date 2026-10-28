package com.strgmai.ace.service.backend;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** #65: the seam `ContextProbe`/`Reviews`/`Judge`/`TaskMatcher`/`PiAgent` go through instead of
 *  inlining oMLX's own filesystem layout, CLI, log format and provider-key convention directly -
 *  so a second local backend (llama.cpp, LM Studio, vLLM) could exist later without touching those
 *  classes again. `OmlxBackend` is the only implementation today; every method here is a verbatim
 *  relocation of logic that already lived in `ContextProbe`, not a redesign. Orchestration that was
 *  already backend-agnostic (`health()`, `serverCap()`, `positionalLimit()`, `derive()`,
 *  `decodeAt()`, `performanceWindow()`, the prepareCap/finalizeCap sequencing) stays in
 *  `ContextProbe` and merely calls through here for the backend-specific leaf operations. */
public interface LocalModelBackend {

    /** This backend's own `provider_key` (e.g. "omlx") - replaces every hardcoded "omlx"/"omlx/"
     *  string literal across Reviews/Judge/TaskMatcher/PiAgent. */
    String providerKey();

    /** True if this backend's own control surface (binary, CLI, management API - whatever it is for
     *  this backend) is present at all. Replaces ContextProbe's `Files.exists(OMLX_BIN)` guards. */
    boolean isAvailable();

    /** Restart (or start, if not running) this backend's own server process; must not return until
     *  `isHealthy` reports true or a bounded timeout elapses, throwing IllegalStateException
     *  otherwise. `isHealthy` is supplied by the caller (ContextProbe already owns the generic
     *  `/health` HTTP check) rather than duplicated here, so this interface stays free of
     *  HTTP/endpoint concerns - restarting a process and verifying liveness are two different
     *  responsibilities. No checked exceptions: on interruption while waiting for the FIRST restart
     *  to come back healthy, this returns silently (interrupt flag re-set, caller sees "not healthy
     *  yet" only if it goes on to check) rather than throwing - a cancelled job must not get stuck
     *  here; any other failure (process start, orphan reconciliation) throws IllegalStateException. */
    void restart(BooleanSupplier isHealthy);

    /** This backend's own configured model directories (wherever it keeps model weights/configs),
     *  in priority order. Empty if the backend exposes no such listing. */
    List<String> modelDirs() throws IOException;

    /** Raise this model's own configured max-context-window setting on disk; returns the previous
     *  value (null if unset) so the caller can restore it later via clearModelCap()/this same method. */
    Object setModelCap(String model, int value) throws IOException;

    /** Clear this model's own configured max-context-window override, putting it back to "unset". */
    void clearModelCap(String model) throws IOException;

    /** Best-effort memory/capacity-guard facts (max usage vs. the ceiling it was sized against) this
     *  backend logged since `since` (its own timestamp-string format - oMLX's is comparable
     *  lexically, so this stays a String rather than forcing every backend through one parsed
     *  Instant format). Empty if the backend exposes no such signal - callers already treat that the
     *  same as "no guard facts available" today. */
    Optional<Map<String, Object>> memoryGuardFacts(String since);

    /** This backend's own settings, as a fingerprint ContextProbe.setupKey() folds into its overall
     *  probe-result cache key (alongside the model-dir config hashes, host memory and Docker-up
     *  facts that are NOT backend-specific and stay in ContextProbe). Whatever changing would mean
     *  "re-probe, don't trust the cache" for this backend. */
    Map<String, Object> settingsFingerprint(String model) throws IOException;

    /** This backend's own log lines in `logFile` whose timestamp falls in [start, end] - encapsulates
     *  the backend's own log line format/timestamp pattern. Used to archive a run's slice of the
     *  backend's server log alongside its results. */
    List<String> logLinesBetween(Path logFile, Instant start, Instant end) throws IOException;
}
