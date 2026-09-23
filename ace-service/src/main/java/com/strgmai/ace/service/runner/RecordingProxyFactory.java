package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.proxy.RecordingProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** The factory RunBench uses for per-session recording proxies (one proxy per phase/task with
 *  that session's own token budget and journal tag; parallel tasks each get their own journal and
 *  tag so one task's kill never reaches a sibling — the Python original spawns one proxy process
 *  per session, this is its in-process twin). */
@Component
public class RecordingProxyFactory {
    private static final Logger log = LoggerFactory.getLogger(RecordingProxyFactory.class);
    private final BenchProperties props;

    public RecordingProxyFactory(BenchProperties props) { this.props = props; }

    /** one proxy session: base() is the model endpoint to point the agent at; abort() closes
     *  whatever is currently relaying through it WITHOUT stopping the listening server - for
     *  giving up on one retry attempt while staying on the same session/budget/journal for the
     *  next one; stop() aborts in-flight streams (they are journaled as drain_aborted) AND tears
     *  the whole proxy down, and returns the exit state. */
    public record ProxySession(String base, Runnable abort, Runnable stop) {}

    public ProxySession start(final Path journal, final Long tokenBudget, final String tag) {
        final var proxy = new RecordingProxy(props);
        final String[] base = new String[1];
        try { base[0] = proxy.start(journal, tokenBudget, tag); }
        catch (Exception e) {
            // release the partially-started server/executor, don't wait for GC - the real cause
            // is already carried by the ISE thrown below, this is just the cleanup-of-cleanup case
            try { proxy.stop(); } catch (Exception cleanupEx) { log.warn("failed to release a partially-started proxy: {}", cleanupEx.toString()); }
            throw new IllegalStateException("cannot start the recording proxy", e);
        }
        return new ProxySession(base[0], proxy::abortInflight, () -> {
            try { proxy.stop(); } catch (Exception e) { log.warn("failed to stop the recording proxy for tag {}: {}", tag, e.toString()); }
        });
    }
}
