package com.strgmai.ace.service.runner;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** An operator's live, mid-task steering message, delivered through the exact same mechanism
 *  Judge already uses (see RunBench.buildJudgeCheckpoint()): checked once per turn, and whatever
 *  is pending gets injected as the agent's next instruction. Unlike Judge, there is no LLM call
 *  and no run-level enablement - an operator can intervene on any task at any time, independent
 *  of whether that task's own judge_enabled is set.
 *
 *  A plain in-memory mailbox, keyed by (runId, taskId) - both already threaded through every call
 *  site that needs them. Not persisted: an intervention is a live command, not state that must
 *  survive an ace-service restart (if the service restarts mid-task, the session restarts too,
 *  same as today). Delivery itself IS durably logged, for free: the returned text (already
 *  wrapped with a marker identifying it as an operator intervention) flows through the same
 *  session.user(...) call Judge's own recommendation already does, landing in the task's own
 *  session JSONL - searchable later across a whole run via
 *  `grep -rl "Operator intervention" results/&lt;run&gt;/sessions/`. */
@Component
public class Intervene {
    private final Map<String, Queue<String>> mailboxes = new ConcurrentHashMap<>();

    /** called from the HTTP endpoint - queues the operator's message for whatever session is
     *  currently live for this task, picked up at its own next turn boundary. Wrapped once, here,
     *  into the exact text the agent will read: framed so it reads as an authoritative human
     *  override rather than ambiguous input, and distinctively marked for later log review. */
    public void offer(final String runId, final String taskId, final String text) {
        mailboxes.computeIfAbsent(key(runId, taskId), k -> new ConcurrentLinkedQueue<>())
                .add("[Operator intervention] A human operator is steering you directly. Follow this instruction:\n\n" + text);
    }

    /** FIFO; null when nothing is pending for this task. */
    public String poll(final String runId, final String taskId) {
        final var q = mailboxes.get(key(runId, taskId));
        return q == null ? null : q.poll();
    }

    /** Called once a task's own main implementation session concludes (whatever the outcome), so
     *  a message the operator sent too late (or mistargeted) doesn't leak into a later, unrelated
     *  attempt at the same task id. */
    public void clear(final String runId, final String taskId) {
        mailboxes.remove(key(runId, taskId));
    }

    private static String key(final String runId, final String taskId) {
        return runId + "/" + taskId;
    }
}
