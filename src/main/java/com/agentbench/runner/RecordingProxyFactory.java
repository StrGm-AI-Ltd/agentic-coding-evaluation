package com.agentbench.runner;

import com.agentbench.config.BenchProperties;
import com.agentbench.proxy.RecordingProxy;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/** The factory RunBench uses for per-session recording proxies (one proxy per phase/task with
 *  that session's own token budget and journal tag; parallel tasks each get their own journal and
 *  tag so one task's kill never reaches a sibling — the Python original spawns one proxy process
 *  per session, this is its in-process twin). */
@Component
public class RecordingProxyFactory {
    private final BenchProperties props;

    public RecordingProxyFactory(BenchProperties props) { this.props = props; }

    /** one proxy session: base() is the model endpoint to point the agent at; stop() aborts
     *  in-flight streams (they are journaled as drain_aborted) and returns the exit state. */
    public record ProxySession(String base, Runnable stop) {}

    public ProxySession start(Path journal, Long tokenBudget, String tag) {
        RecordingProxy proxy = new RecordingProxy(props);
        final String[] base = new String[1];
        try { base[0] = proxy.start(journal, tokenBudget, tag); }
        catch (Exception e) { throw new IllegalStateException("cannot start the recording proxy", e); }
        return new ProxySession(base[0], () -> {
            try { proxy.stop(); } catch (Exception ignore) {}
        });
    }

    public static final List<Integer> TERMINAL_PROXY_STATES = List.of(0);
}
