package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.AgentSession;
import com.strgmai.ace.service.agent.ProviderStore;
import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import com.strgmai.ace.service.plan.PlanTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** #339: a standalone, post-hoc "match tasks between two already-finished runs" primitive for the
 *  Compare tab. Two independently-decomposed --plan-source=agent runs of the same task spec number
 *  and scope their own tasks completely independently (different ids, different scope boundaries,
 *  sometimes a different implementation language), so comparing them feature-by-feature needs
 *  semantic matching, not an index lookup. Reuses #322's own proposed mechanism (an LLM call
 *  judging the closest match, or declining) as a standalone comparison tool, independent of
 *  whether #322's own live automation loop ever ships.
 *
 *  Deliberately duplicates Judge/Reviews' own one-shot-LLM-call scaffolding (reviewer-string
 *  resolution, external credentials/base-URL) rather than factoring out a shared helper - see
 *  Judge.java's own class javadoc for why that's the established convention here, not an
 *  oversight. Unlike Judge/Reviews, there is no run `cfg` map at all (this isn't tied to any run's
 *  own invocation) - every call here is self-contained, driven only by its own arguments. */
@Component
public class TaskMatcher {
    private static final Logger log = LoggerFactory.getLogger(TaskMatcher.class);
    private final BenchProperties props;
    private final ReferenceAgent agent;
    private final ProviderStore providerStore;

    public TaskMatcher(final BenchProperties props, final ReferenceAgent agent, final ProviderStore providerStore) {
        this.props = props;
        this.agent = agent;
        this.providerStore = providerStore;
    }

    private static final int MAX_GOAL_CHARS = 400;   // same "character caps, not token caps" convention Judge uses
    private static final int MAX_TURNS = 4;

    public record TaskMatch(String aId, String aTitle, String bId, String bTitle) {}

    private static final String SYSTEM = """
            You are matching tasks between two independently-planned implementations of the SAME
            task specification. Each plan numbered and split its own work independently - task ids
            and scope boundaries do not correspond between the two lists at all. For every task in
            list A, find the task in list B that implements the closest matching feature, judging
            by what each task actually builds (its goal/acceptance text), not by id or position. A
            task may legitimately have no match (list B split or omitted that feature differently).
            Never invent a task id that isn't in the list you were given.

            Answer with exactly one line per task in list A, in list A's own order, in this exact
            format and nothing else:
            <A task id> -> <B task id or NONE>""";

    /** one bounded, non-tool-using LLM call judging the closest match for every task in aTasks
     *  against bTasks. reviewerModel follows Judge/Reviews' own "omlx/&lt;model&gt;" (local,
     *  default) vs "provider/model" (external) convention. rd is where the prompt/reply get logged
     *  for inspection - the caller's choice (BenchController uses run A's own
     *  resultsDir/compare/ subfolder, kept out of that run's own benchmark data). */
    public List<TaskMatch> match(final List<PlanTask> aTasks, final List<PlanTask> bTasks,
                                  final String reviewerModel, final Path rd) throws Exception {
        if (aTasks.isEmpty() || bTasks.isEmpty()) return List.of();
        final String prompt = buildPrompt(aTasks, bTasks);
        final String reviewer = providerStore.resolveModel(reviewerModel, "omlx/" + props.model());
        final boolean external = !reviewer.startsWith("omlx/");
        final String model = reviewer.startsWith("omlx/") ? reviewer.substring("omlx/".length()) : reviewer.substring(reviewer.indexOf('/') + 1);
        final Map<String, String> extraEnv = external ? externalCredentials() : null;
        final Consumer<String> abort = reason -> {};
        final String name = "task-match-" + System.currentTimeMillis();
        final String sid = UUID.randomUUID().toString();
        final ReferenceAgent.SessionResult res = RunBenchSupport.runBounded(0, name, rd.resolve("sessions"), sid, abort,
                () -> agent.run(name, prompt, 0, 0L, rd.resolve("sessions"), sid, false, SYSTEM, rd.toString(),
                        externalBase(reviewer), abort, 0, props.compactionTrigger(), MAX_TURNS, model, extraEnv, null, true));
        final String reply = AgentSession.lastAssistantText(res.sessionFile()).strip();
        Files.createDirectories(rd.resolve("compare"));
        Files.writeString(rd.resolve("compare").resolve(name + ".md"),
                "# Task match: " + name + "\n\n## Prompt\n\n" + prompt + "\n\n## Reply\n\n" + (reply.isEmpty() ? "(none)" : reply) + "\n");
        return parseReply(reply, aTasks, bTasks);
    }

    private static String buildPrompt(final List<PlanTask> aTasks, final List<PlanTask> bTasks) {
        final StringBuilder sb = new StringBuilder("## List A\n\n");
        for (final PlanTask t : aTasks) sb.append(taskLine(t)).append('\n');
        sb.append("\n## List B\n\n");
        for (final PlanTask t : bTasks) sb.append(taskLine(t)).append('\n');
        return sb.toString();
    }

    private static String taskLine(final PlanTask t) {
        final String goal = t.goal == null || t.goal.isBlank() ? t.title : t.goal;
        return "- " + t.id + ": " + cap(goal == null ? "" : goal, MAX_GOAL_CHARS);
    }

    private static String cap(final String text, final int n) {
        return text.length() <= n ? text : text.substring(0, n) + "… [truncated]";
    }

    /** one "<aId> -> <bId-or-NONE>" line per A task, in A's own order - tolerant of extra prose
     *  around the expected lines (same resilience convention as Packs.parseSelfReview): an
     *  unparseable or missing line for a given A task, or a B id the model invented that isn't
     *  actually in bTasks, both fall back to "no match" rather than failing the whole call. */
    static List<TaskMatch> parseReply(final String reply, final List<PlanTask> aTasks, final List<PlanTask> bTasks) {
        final Pattern linePattern = Pattern.compile("^\\s*(\\S+)\\s*->\\s*(\\S+)\\s*$");
        final Map<String, String> byAId = new LinkedHashMap<>();
        for (final String line : reply.split("\n")) {
            final Matcher m = linePattern.matcher(line.strip());
            if (m.matches()) byAId.put(m.group(1), m.group(2));
        }
        final Map<String, String> bTitleById = new LinkedHashMap<>();
        for (final PlanTask t : bTasks) bTitleById.put(t.id, t.title);
        final List<TaskMatch> out = new ArrayList<>();
        for (final PlanTask a : aTasks) {
            final String bId = byAId.get(a.id);
            final boolean matched = bId != null && !"NONE".equalsIgnoreCase(bId) && bTitleById.containsKey(bId);
            out.add(new TaskMatch(a.id, a.title, matched ? bId : null, matched ? bTitleById.get(bId) : null));
        }
        return out;
    }

    /** #106/LLM-Providers: was its own copy of the provider table (duplicated across Reviews/Judge/
     *  TaskMatcher); now a direct call into the shared registry. */
    Map<String, String> externalCredentials() {
        return providerStore.allCredentials();
    }

    /** external providers bypass the proxy (their credentials and endpoints are their own) -
     *  same convention as Judge.externalBase()/Reviews.externalBase(), duplicated rather than
     *  shared; simpler here since there is no run `cfg` to fall back to.
     *
     *  Found live: an unrecognized provider prefix (including the historical "omlx" convention
     *  baked into resolveModel()'s own local-fallback string - see match() above - on a
     *  deployment whose local provider happens to be registered under a DIFFERENT key, e.g.
     *  "omlx-local") used to fall through to props.endpoint() directly. That property returns
     *  the raw ACE_ENDPOINT value with no blank-check of its own - an empty string (not unset;
     *  Spring treats "set to empty" as a real value, never falling through to the Java-side
     *  default) reached the OpenAI SDK client builder as-is, which rejects it outright with a
     *  bare "baseUrl cannot be null or blank", no indication of WHICH provider lookup actually
     *  failed. props.upstreamBase() has its own real fallback for exactly this blank case
     *  (confirmed live in Preflight.java's own equivalent fix) - using it here can't produce a
     *  blank baseUrl, so a genuinely misconfigured/renamed provider now fails with a real
     *  connection error against a real (if possibly unreachable) address instead. */
    String externalBase(final String reviewer) {
        final int slash = reviewer.indexOf('/');
        final String provider = slash < 0 ? "" : reviewer.substring(0, slash);
        final var known = providerStore.byKey(provider);
        return known != null ? known.baseUrl() : props.upstreamBase() + "/v1";
    }
}
