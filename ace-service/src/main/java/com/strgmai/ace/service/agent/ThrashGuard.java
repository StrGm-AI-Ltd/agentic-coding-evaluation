package com.strgmai.ace.service.agent;

import java.util.Map;
import java.util.regex.Pattern;

/** #326: a cheap, deterministic, same-turn circuit breaker for the cheapest, most mechanical
 *  failure mode an agent loop can hit - the SAME tool call (name + arguments), verbatim, several
 *  times in a row. Trajectory.java's own post-hoc "identical_calls_repeated" metric already proves
 *  this pattern is real and common, but only discovers it once the whole session is already over;
 *  the only IN-LOOP intervention before this was the LLM-based Judge, every judge_every_n_turns
 *  (default 10) turns, at real token/latency cost. This complements it, not replaces it: fires
 *  within THRESHOLD calls at zero LLM cost, on every session kind (not just a task's own main
 *  session), regardless of whether the judge is even enabled - the judge stays free to focus on
 *  subtler drift a mechanical check can't see.
 *
 *  Mirrors CompactionStrategies.State's own convention: mutable, single-session-scoped, owned
 *  exclusively by ReferenceAgent.run()'s own turn loop - no concurrency concern (the loop that
 *  calls check() already owns `msgs`, and so this state, exclusively). */
final class ThrashGuard {
    private ThrashGuard() {}

    /** #326: Trajectory.java's own identical_calls_repeated threshold (5) counts repeats pooled
     *  across the WHOLE session after the fact; a same-turn, in-a-row check can and should fire
     *  sooner, per the issue's own framing ("near-instantly - within 2-3 turns") - there is no
     *  reason to let a 4th identical call happen once the first 3 already prove the model isn't
     *  adapting. */
    static final int THRESHOLD = 3;

    /** the same bash test/build exemption Trajectory.isTestOrBuild() already applies before
     *  counting a repeat as thrash ("re-running the tests after an edit is discipline, not
     *  thrash") - duplicated rather than cross-wired: agent/ and metrics/ have no existing
     *  dependency either direction, and Judge.java's own javadoc already sets the precedent for
     *  duplicating this exact kind of small, stable check rather than introducing one. */
    private static final Pattern TEST_CMD = Pattern.compile("gradle|gradlew|pytest|npm test|mvn|\\bmake\\b");

    static final class State {
        private String lastSignature;
        private int consecutiveCount;
    }

    /** `toolName`/`argsJson` are exactly what the turn loop already has for a just-executed call
     *  (ToolExecutionRequest.name()/arguments() - no re-parsing needed for the signature itself);
     *  `args` is the SAME already-parsed arguments map the loop builds for executeTool(), reused
     *  here only for the test/build exemption check. Returns a harness-authored corrective message
     *  the FIRST time the running streak reaches THRESHOLD - not on every call after that (the
     *  model may still be stuck; repeating the identical nudge every turn would just be more noise
     *  for it to ignore) - the streak must break and rebuild to THRESHOLD again before this fires a
     *  second time. null means "nothing to inject for this call." */
    static String check(final String toolName, final String argsJson, final Map<String, Object> args, final State state) {
        if (isTestOrBuild(toolName, args)) { state.lastSignature = null; state.consecutiveCount = 0; return null; }
        final String sig = toolName + "|" + argsJson;
        if (sig.equals(state.lastSignature)) state.consecutiveCount++;
        else { state.lastSignature = sig; state.consecutiveCount = 1; }
        if (state.consecutiveCount != THRESHOLD) return null;
        state.consecutiveCount = 0;   // the streak must rebuild from zero to fire again
        return "You have called " + toolName + " with the exact same arguments " + THRESHOLD
                + " times in a row - repeating it again will not produce a different result. Stop. "
                + "Read the actual output/error from the last call and change your approach before "
                + "calling any tool again.";
    }

    private static boolean isTestOrBuild(final String toolName, final Map<String, Object> args) {
        if (!"bash".equals(toolName)) return false;
        final Object cmd = args.get("command");
        return cmd instanceof String s && TEST_CMD.matcher(s).find();
    }
}
