package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.proxy.RecordingProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The factory RunBench uses for per-session recording proxies (one proxy per phase/task with
 *  that session's own token budget and journal tag; parallel tasks each get their own journal and
 *  tag so one task's kill never reaches a sibling — the Python original spawns one proxy process
 *  per session, this is its in-process twin). */
@Component
public class RecordingProxyFactory {
    private static final Logger log = LoggerFactory.getLogger(RecordingProxyFactory.class);
    private final BenchProperties props;
    // every currently-active session's own abort callback - WorkerService's cancel-watch thread
    // calls abortAllInflight() proactively on a pause/cancel, the moment it notices the flag,
    // rather than relying solely on Thread.interrupt() to reach whatever is actually blocking (a
    // raw blocking HTTP read does not reliably honor it - see abortAllInflight()'s own javadoc). A
    // Set, not a single field: a parallel wave can have more than one session running at once.
    private final Set<Consumer<String>> active = ConcurrentHashMap.newKeySet();

    public RecordingProxyFactory(BenchProperties props) { this.props = props; }

    /** one proxy session: base() is the model endpoint to point the agent at; abort(reason) closes
     *  whatever is currently relaying through it WITHOUT stopping the listening server - for
     *  giving up on one retry attempt while staying on the same session/budget/journal for the
     *  next one; the reason is journaled against the aborted request. stop() aborts in-flight
     *  streams (they are journaled as drain_aborted) AND tears the whole proxy down, and returns
     *  the exit state. */
    public record ProxySession(String base, Consumer<String> abort, Runnable stop) {}

    public ProxySession start(final Path journal, final Long tokenBudget, final String tag) {
        return start(journal, tokenBudget, tag, RecordingProxy.SamplerOverrides.NONE);
    }

    public ProxySession start(final Path journal, final Long tokenBudget, final String tag, final RecordingProxy.SamplerOverrides overrides) {
        final var proxy = new RecordingProxy(props);
        final String[] base = new String[1];
        try { base[0] = proxy.start(journal, tokenBudget, tag, overrides); }
        catch (Exception e) {
            // release the partially-started server/executor, don't wait for GC - the real cause
            // is already carried by the ISE thrown below, this is just the cleanup-of-cleanup case
            try { proxy.stop(); } catch (Exception cleanupEx) { log.warn("failed to release a partially-started proxy: {}", cleanupEx.toString()); }
            throw new IllegalStateException("cannot start the recording proxy", e);
        }
        final Consumer<String> abort = proxy::abortInflight;
        active.add(abort);
        return new ProxySession(base[0], abort, () -> {
            active.remove(abort);
            try { proxy.stop(); } catch (Exception e) { log.warn("failed to stop the recording proxy for tag {}: {}", tag, e.toString()); }
        });
    }

    /** Proactively severs every currently-active session's own in-flight connection - the same
     *  abortInflight() mechanism each ProxySession's own abort() already exposes, generalized to
     *  every session live right now (a parallel wave can have more than one). Called by
     *  WorkerService's cancel-watch thread the instant it notices a pause/cancel request, instead
     *  of only reactively after RunBenchSupport.runBounded()'s own Future.get() has already given
     *  up on the WAITING thread - closing the gap where the waiting side reports the job stopped
     *  while the WORKING thread (blocked in a non-interruptible read, same class of bug as #R14/
     *  pre-R12) is still genuinely running and still writing real turns to the session file. */
    public void abortAllInflight(final String reason) {
        for (final Consumer<String> abort : active) abort.accept(reason);
    }
}
