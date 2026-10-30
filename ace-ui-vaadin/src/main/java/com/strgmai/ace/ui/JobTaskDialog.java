package com.strgmai.ace.ui;

import com.github.appreciated.apexcharts.ApexCharts;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Extracted from JobDetailView (Phase 1 of closing the live-monitoring gap on the new main Job
 *  detail page - see the plan this followed). The live per-task dialog: Prompt/Changes/Restart/
 *  History/Judge/Stats/Live(+intervene), the LIVE counterpart of RunTaskDialog (same precedent this
 *  follows - a separate top-level class calling back into JobDetailView's own static, package-
 *  private chart/formatting helpers (taskStatsLines, attemptSections, buildRequestSpeedChart/
 *  updateRequestSpeedChart, buildRequestTimingChart/updateRequestTimingChart,
 *  addCompactionEffectivenessTables) unchanged, rather than duplicating them) plus the two things a
 *  finished run's own dialog never needs: a Restart tab (needs a pausable job) and a Live tab with
 *  an intervene box (needs an active session to steer).
 *
 *  <p>{@code currentJob} is a Supplier, not a snapshot - the dialog polls for as long as it stays
 *  open, and the Restart tab's own paused-check needs the job's CURRENT state on every rebuild, not
 *  whatever it was the moment the dialog opened. {@code ownerView} is the enclosing view's own root
 *  Component (JobDetailView passes {@code this}) - the Live tab's transcript stream needs to stop on
 *  EITHER the dialog's own close OR the owning view detaching (navigating away), and a Dialog's own
 *  overlay isn't torn down just because the view that opened it navigates away, so the owner's own
 *  detach listener is the only way to catch the second case. */
final class JobTaskDialog {
    private static final Logger log = LoggerFactory.getLogger(JobTaskDialog.class);

    /** The "Live" tab: a plain-text, operator-facing peek into the task's own coding session -
     *  what it's thinking, saying, and calling tools to do - streamed from
     *  /jobs/{id}/tasks/{taskId}/transcript (TaskTranscript on the server side). Unlike every
     *  other tab in this dialog, its content arrives from a background SSE thread for as long as
     *  the dialog stays open, not from the owning view's own 2s poll cycle - same push mechanism
     *  the main page's own ensureSse()/JobEventLoop already use (this app runs with @Push
     *  enabled), just scoped to one task's dialog instead of the whole page. Capped client-side
     *  too (CLIENT_CAP_CHARS) - the server's own initial catch-up batch is already tail-capped,
     *  but a dialog left open for hours would otherwise still grow its own buffer unboundedly. */
    private static final int TRANSCRIPT_CLIENT_CAP_CHARS = 40_000;

    private JobTaskDialog() {
    }

    /** The task's prompt and its own git changes, on demand, as two tabs of one dialog. Polling
     *  (piggybacking the owning view's own poll cycle) keeps BOTH tabs fresh while either still has
     *  something left to learn, and stops once both have settled: the prompt once its real, dynamic
     *  part has arrived (taskPrompt.started) or is confirmed never coming (#258 - a task finished in
     *  an earlier, since-discarded attempt); the diff once the task's own phase tag exists
     *  (taskDiff.finished) - until then the diff is the live, uncommitted working tree, which keeps
     *  changing for as long as the task keeps running. */
    static void open(final ServiceClient client, final String jobId, final Api.PlanTask task,
            final Supplier<Api.Job> currentJob, final JobLiveState live, final Component ownerView, final Runnable onChanged) {
        // #575, found live: the Plan grid's own row-click event carries an item key resolved
        // against whichever data provider the server happens to have at the moment the click's own
        // RPC round-trip is actually processed - the grid's poll cycle replaces the whole item list
        // wholesale (setItems(planTasks)) every ~2s, and a click landing in that narrow window
        // resolves to no item at all. A null task here used to reach task.id() on the very next
        // line as an unguarded NullPointerException, surfaced to DefaultErrorHandler - the click
        // target evaporated under a concurrent refresh, not an operator-facing error worth a
        // notification, so this is a quiet no-op instead.
        if (task == null) return;
        final var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(task.id() + (task.title() == null || task.title().isBlank() ? "" : " — " + task.title()));
        dialog.setWidth("min(900px, 90vw)");
        dialog.setHeight("min(650px, 85vh)");

        // each body below gets the SAME explicit "width: 100%; box-sizing: border-box" fix
        // transcriptBody/interveneRow already needed and explain further down: a VerticalLayout
        // does not stretch its children to its own width by default, so without it every tab's
        // content (and the tab strip itself) only grows as wide as its own content - its right
        // edge lands wherever that happens to be instead of the dialog's own right edge, where
        // the footer's Close button sits (found live: visibly misaligned against it).
        final var promptBody = new Div();
        promptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        final var diffBody = new Div();
        diffBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        diffBody.setVisible(false);
        final var restartBody = new Div();
        restartBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        restartBody.setVisible(false);
        final var historyBody = new Div();
        historyBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        historyBody.setVisible(false);
        final var judgeBody = new Div();
        judgeBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        judgeBody.setVisible(false);
        final var statsBody = new Div();
        statsBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        statsBody.setVisible(false);
        // on demand (see loadStats/loadStatsOnSelect below): a manual way to pull fresh stats
        // without waiting for the next poll tick or leaving and re-entering the tab. Lives in the
        // DIALOG'S OWN footer, not inside statsBody - Vaadin Dialog renders header/content/footer
        // as three separate regions, where only the middle (content) scrolls; a footer child stays
        // fixed in place regardless of how far the operator has scrolled statsBody's own charts, with
        // no hand-rolled CSS position:sticky needed. Shared with every other tab's footer row, so
        // its own visibility is toggled alongside each body's below - only meaningful while Stats is
        // actually the selected tab.
        final var refreshStatsButton = new Button("Refresh");
        refreshStatsButton.setVisible(false);
        // statsSummaryBody (the top text lines) is cleared and rebuilt every poll, same as before -
        // statsAttemptsBody is a SIBLING, same reason transcriptBody/interveneRow are siblings below:
        // a chart inside it must survive statsSummaryBody's own removeAll() every 2s, or every chart
        // gets torn down and rebuilt from scratch on every poll tick even when no new data exists -
        // the operator asked for a running task's own graphs to redraw only when a request actually
        // finishes, not on a fixed clock. See loadStats below for how each attempt's own chart area
        // is gated.
        final var statsSummaryBody = new Div();
        final var statsAttemptsBody = new Div();
        statsBody.add(statsSummaryBody, statsAttemptsBody);
        // the operator-facing live transcript - see openTaskTranscriptTab() below for why this
        // is its own method rather than inlined like the bodies above (its content keeps
        // growing from a background thread for as long as the dialog stays open, unlike every
        // other tab here which is just rebuilt wholesale on each poll tick).
        final var transcriptBody = new Div();
        // an explicit width, not just "auto": transcriptBody is a flex item of the content
        // VerticalLayout below, which does not stretch its children to its own width by default -
        // without this, a long unbroken line in the transcript (a file path, a JSON blob) has no
        // definite containing width to wrap/scroll against, so it balloons transcriptBody AND the
        // whole dialog outward instead of scrolling horizontally within its own box, same as a
        // too-narrow interveneRow (below) would otherwise mis-size its own Send button row.
        transcriptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        transcriptBody.setVisible(false);
        // a SIBLING of transcriptBody, never nested inside it - transcriptBody.removeAll() runs on
        // every single incoming SSE line (see openTaskTranscriptTab()), which would wipe an input
        // box (and any not-yet-sent, half-typed text) on every streaming update if it lived inside.
        // #568, found live: this used to sit in the scrollable CONTENT area, right after the
        // transcript - visually stranded there with a big gap before the dialog's own Close button,
        // which sits in the FOOTER. Lives in the footer now instead, the same pane Close (and
        // refreshStatsButton below) already use - flex-grow so it claims the space Close doesn't
        // need, same reasoning as the Send button's own flex-grow on the text field beside it. NOT
        // width:100% though (found live, #568 follow-up): on a flex ITEM that sets flex-basis to
        // 100% too, which claims the ENTIRE footer row for this Div alone and wraps Close onto its
        // own second line below it - the exact fight flex-grow-not-width:100% avoids one level down,
        // between the text field and the Send button, repeated here one level up by mistake.
        final var interveneRow = new Div();
        interveneRow.getStyle().set("box-sizing", "border-box").set("flex-grow", "1");
        interveneRow.setVisible(false);

        final var promptTab = new Tab("Prompt");
        final var changesTab = new Tab("Changes");
        final var restartTab = new Tab("Restart");
        final var historyTab = new Tab("History");
        final var judgeTab = new Tab("Judge");
        final var statsTab = new Tab("Stats");
        final var transcriptTab = new Tab("Live");
        final var tabs = new Tabs(promptTab, changesTab, restartTab, historyTab, judgeTab, statsTab, transcriptTab);
        tabs.getStyle().set("width", "100%").set("box-sizing", "border-box");
        // a pipeline stage (definition/plan/parallel-plan/self-review) has no Restart tab - the
        // per-task restart endpoint it would call assumes a "phase/" + id git tag, which doesn't
        // match any of these stages' own irregular tag names (see Api.PlanTask.stage()'s own
        // javadoc), so restarting one from here would just fail with a confusing server error
        // instead of doing nothing visibly wrong.
        restartTab.setVisible(!task.stage());
        // loadStatsOnSelect is set below, once loadStats itself exists - the listener only needs
        // to know the Stats tab was just selected, not run it inline. #541-followup: Stats is now
        // loaded ON DEMAND (only while actually selected), not on every poll tick regardless of
        // which tab is showing - this is what makes "the chart's real data is already in hand by
        // the time it's first built" (see buildRequestSpeedChart()'s javadoc) true in the first
        // place: a chart built while the dialog sits on the Prompt tab would have no real data to
        // embed yet, forcing it back onto the old build-empty-then-updateSeries() pattern.
        final Runnable[] loadStatsOnSelect = new Runnable[1];
        tabs.addSelectedChangeListener(e -> {
            promptBody.setVisible(tabs.getSelectedTab() == promptTab);
            diffBody.setVisible(tabs.getSelectedTab() == changesTab);
            restartBody.setVisible(tabs.getSelectedTab() == restartTab);
            historyBody.setVisible(tabs.getSelectedTab() == historyTab);
            judgeBody.setVisible(tabs.getSelectedTab() == judgeTab);
            statsBody.setVisible(tabs.getSelectedTab() == statsTab);
            transcriptBody.setVisible(tabs.getSelectedTab() == transcriptTab);
            interveneRow.setVisible(tabs.getSelectedTab() == transcriptTab);
            refreshStatsButton.setVisible(tabs.getSelectedTab() == statsTab);
            if (tabs.getSelectedTab() == statsTab && loadStatsOnSelect[0] != null) loadStatsOnSelect[0].run();
        });

        final var content = new VerticalLayout(tabs, promptBody, diffBody, restartBody, historyBody, judgeBody, statsBody, transcriptBody);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(interveneRow, refreshStatsButton, new Button("Close", e -> dialog.close()));

        final Registration[] pollReg = new Registration[1];
        // #256, found live: Packs.taskPack() caps the whole id/title/goal/services/acceptance
        // summary block to a fixed prompt-budget size (a real, deliberate cost/context control on
        // what the MODEL receives, not a bug) - so the version embedded in the raw prompt can cut
        // "acceptance" off mid-sentence once "goal"/"services" alone already used the budget. The
        // plan's own goal text (task.goal(), from PlanParser - never capped there) is shown here in
        // full regardless, since there's no reason the ACE user watching this page should be bound
        // by the same budget the model's own prompt is deliberately held to.
        final boolean hasGoal = task.goal() != null && !task.goal().isBlank();
        final boolean[] promptSettled = {false};
        final boolean[] diffSettled = {false};

        final Runnable loadPrompt = () -> {
            try {
                final var prompt = client.taskPrompt(jobId, task.id());
                promptBody.removeAll();
                if (hasGoal) {
                    promptBody.add(new com.vaadin.flow.component.Html(
                            "<div class=\"md-body\"><h3>Full task description</h3>" + Markdown.toHtml(task.goal()) + "</div>"));
                }
                // #258, found live: a task can be genuinely done (per its own R18 git tag) while its
                // results dir never got packs/{id}.md - it finished during an earlier, since-discarded
                // attempt (cancelled before the pause fix existed, or otherwise aborted) and R18 then
                // skipped re-running it on resume, so the pack was never rewritten into the new attempt's
                // results dir either. Without this check that read as "hasn't started yet", which is
                // simply false and - worse - would poll forever, since the real prompt is never coming.
                final boolean finishedElsewhere = task.implemented() || task.handoff_done();
                if (!prompt.started()) {
                    final var note = new Span(finishedElsewhere
                            ? "This task already finished in an earlier attempt whose results were discarded "
                              + "before this one resumed, so the enriched prompt from that attempt no longer "
                              + "exists - only its fixed instruction is shown below."
                            : "This task hasn't started yet - the prompt below is its fixed "
                              + "instruction only; the rest depends on the workspace state at the moment it actually "
                              + "begins. This updates automatically once it does.");
                    note.getStyle().set("font-size", "0.85em")
                            .set("display", "block").set("margin-bottom", "8px");
                    promptBody.add(note);
                    promptSettled[0] = finishedElsewhere;
                } else {
                    promptSettled[0] = true;
                }
                promptBody.add(new com.vaadin.flow.component.Html("<div class=\"md-body\">" + Markdown.toHtml(prompt.text()) + "</div>"));
            } catch (final Exception ex) {
                promptBody.removeAll();
                promptBody.add(Panels.error(client.errorText(ex)));
                promptSettled[0] = true;
            }
        };
        // #259: the task's own git diff - the previous state of the repo vs what it changed, or is
        // still in the middle of changing. See BenchController.taskDiff() for before/after semantics.
        final Runnable loadDiff = () -> {
            try {
                final var diff = client.taskDiff(jobId, task.id());
                diffBody.removeAll();
                diffBody.add(DiffView.render(diff));
                diffSettled[0] = diff.finished();
            } catch (final Exception ex) {
                diffBody.removeAll();
                diffBody.add(Panels.error(client.errorText(ex)));
                diffSettled[0] = true;
            }
        };
        // #263: every past attempt at this task's own main session, newest first - read-only, so
        // (unlike the Restart tab's own form below) safe to rebuild on every poll tick the same way
        // Prompt/Changes already are.
        final Runnable loadHistory = () -> {
            try {
                final var attempts = client.taskAttempts(jobId, task.id());
                historyBody.removeAll();
                if (attempts.isEmpty()) {
                    historyBody.add(Panels.mono("(no attempts recorded yet)"));
                    return;
                }
                final int n = attempts.size();
                for (int i = 0; i < n; i++) {
                    final var a = attempts.get(i);
                    final var header = new H4(
                            "Attempt " + (n - i) + " — " + (a.attempt_started() == null ? "?" : a.attempt_started())
                                    + " → " + (a.attempt_ended() == null ? "?" : a.attempt_ended()));
                    header.getStyle().set("margin", "12px 0 4px 0").set("font-size", "0.95em");
                    historyBody.add(header);
                    final var text = new StringBuilder();
                    text.append("params: ").append(a.params()).append(a.overridden() ? " (overridden)" : " (run default)").append('\n');
                    text.append("rc: ").append(a.rc()).append("  seconds: ").append(a.seconds()).append('\n');
                    text.append("outcome: ").append(a.reported_status() == null ? "(none yet)" : a.reported_status());
                    if (a.reported_detail() != null && !a.reported_detail().isBlank()) text.append(" - ").append(a.reported_detail());
                    historyBody.add(Panels.mono(text.toString()));
                }
            } catch (final Exception ex) {
                historyBody.removeAll();
                historyBody.add(Panels.error(client.errorText(ex)));
            }
        };
        // #265: the mid-task judge's own checkpoints for this task, ascending by turn - a timeline
        // of "is this still on track", not a list of outcomes to compare like History above. The
        // recommendation is shown up front; the full prompt it was given is collapsed behind
        // Details, same as CompareView already does for its own verbose, secondary output.
        final Runnable loadJudge = () -> {
            try {
                final var checkpoints = client.taskJudge(jobId, task.id());
                judgeBody.removeAll();
                if (checkpoints.isEmpty()) {
                    judgeBody.add(Panels.mono("(no judge checkpoints yet - either the judge isn't "
                            + "enabled for this task, or it hasn't reached its first interval)"));
                    return;
                }
                for (final var c : checkpoints) {
                    final var header = new H4("Turn " + c.turn());
                    header.getStyle().set("margin", "12px 0 4px 0").set("font-size", "0.95em");
                    judgeBody.add(header);
                    judgeBody.add(Panels.mono(c.recommendation()));
                    judgeBody.add(new com.vaadin.flow.component.details.Details("Prompt the judge saw", Panels.mono(c.prompt())));
                }
            } catch (final Exception ex) {
                judgeBody.removeAll();
                judgeBody.add(Panels.error(client.errorText(ex)));
            }
        };
        // #355/#355-followup: full operator visibility into this task, active or completed -
        // turns/compactions/tool_errors/tool_calls are now ALWAYS read live from the task's own
        // session file by BenchController.taskStats() (never a one-time manifest.json snapshot,
        // which #358 found can be flat wrong for a timed-out attempt) - whether the task is still
        // running, in its own post-session wrap-up/rescue/handoff tail, or long since done. Only
        // the still-live WALL TIME figure for a not-yet-finished task needs a frontend-side
        // fallback at all now (state != "done"): the SAME live JobLiveState this page's own
        // Sessions grid already uses for its own time/token columns (JobDetailView.taskStats(
        // sessions, taskId, currentStep)) - it already sums correctly across a retried/continued
        // task's several sessions and handles a still-running one's wall time (open-ended until
        // "now" or the next task's own start), rather than reporting null until the session ends.
        // one persistent UI slot per attempt (textBody: header+body, refreshed every poll like the
        // summary lines; chartsBody: built once below - speedChart/timingChart are the SAME chart
        // instances for this attempt's whole lifetime, updated in place via updateSeries()/setXaxis()
        // (#517 B) rather than torn down and rebuilt, never touched at all for an already-finished
        // attempt once its last real update has landed).
        record AttemptChartUi(Div textBody, Div chartsBody, ApexCharts speedChart, ApexCharts timingChart) {
        }
        final List<AttemptChartUi> attemptUis = new ArrayList<>();
        // identifies which attempt owns the currently-charted data and how many requests it had
        // last time its chart was drawn - the ONLY attempt still eligible to redraw is the current
        // LAST one (every earlier attempt is already finished, so its own chart never changes once
        // drawn), and even that one redraws only when a new request has actually completed (its
        // own request_speeds array grew), never on a bare poll tick with nothing new to show.
        final int[] lastChartedAttemptIndex = {-1};
        final int[] lastChartedPointCount = {-1};
        final Runnable loadStats = () -> {
            try {
                final var stats = client.taskStats(jobId, task.id());
                statsSummaryBody.removeAll();
                if ("not_started".equals(stats.path("state").asText(null))) {
                    statsSummaryBody.add(Panels.mono("(not started yet)"));
                    statsAttemptsBody.removeAll();
                    attemptUis.clear();
                    lastChartedAttemptIndex[0] = -1;
                    lastChartedPointCount[0] = -1;
                    return;
                }
                statsSummaryBody.add(JobDetailView.taskStatsTileGrid(stats, JobDetailView.taskStats(live.sessions(), task.id(), live.currentStep())));
                // only worth its own text breakdown once there's more than one attempt to tell
                // apart - a single-attempt task would just duplicate the summary lines just added
                // above. Every attempt that actually made a request still gets its own chart
                // regardless (the operator asked for one per attempt, not one for the whole task) -
                // a single-attempt task's own chart just doesn't need an "Attempt N" label on it.
                final var sections = JobDetailView.attemptSections(stats);
                final var multiAttempt = sections.size() > 1;
                while (attemptUis.size() < sections.size()) {
                    final var newIndex = attemptUis.size();
                    final var newSection = sections.get(newIndex);
                    final var attemptLabel = multiAttempt ? " — Attempt " + (newIndex + 1) : "";
                    // #541-followup, root-caused: this attempt's real data is already in hand here
                    // (newSection), so it's embedded directly at each chart's own construction
                    // instead of a separate updateSeries() call moments later - see
                    // buildRequestSpeedChart()'s own javadoc for the mount-race this avoids.
                    final var speedChart = JobDetailView.buildRequestSpeedChart(newSection.requestSpeeds(),
                            newSection.compactionsBeforeRequest(), newSection.judgeOnTrackRequests(), newSection.judgeOffTrackRequests());
                    final var timingChart = JobDetailView.buildRequestTimingChart(newSection.requestSpeeds(),
                            newSection.compactionsBeforeRequest(), newSection.judgeOnTrackRequests(), newSection.judgeOffTrackRequests());
                    final var chartsBody = new Div();
                    chartsBody.add(Panels.card("Request throughput" + attemptLabel, speedChart),
                            Panels.card("Request timing" + attemptLabel, timingChart));
                    final var ui = new AttemptChartUi(new Div(), chartsBody, speedChart, timingChart);
                    attemptUis.add(ui);
                    statsAttemptsBody.add(ui.textBody(), ui.chartsBody());
                    // this chart already shows newSection's data in full (just embedded above) - the
                    // redraw loop below must see it as already up to date, or it would immediately
                    // updateSeries() it again in this SAME pass and reintroduce the very race this
                    // construction-time embed exists to avoid.
                    lastChartedAttemptIndex[0] = newIndex;
                    lastChartedPointCount[0] = newSection.requestSpeeds().size();
                }
                for (int i = 0; i < sections.size(); i++) {
                    final var section = sections.get(i);
                    final var ui = attemptUis.get(i);
                    // the compaction tables below are worth showing for every attempt, whether or
                    // not there's more than one of them - unlike the header/body text (which would
                    // just duplicate the single-attempt summary above), so textBody() is always
                    // cleared and rebuilt, not just when multiAttempt
                    ui.textBody().removeAll();
                    if (multiAttempt) {
                        // #517 (A): the card's own sectionTitle() replaces the bare H4 this used to
                        // be - same information, now wearing the rest of the app's own panel chrome
                        ui.textBody().add(Panels.card(section.header(), Panels.mono(section.body())));
                    }
                    JobDetailView.addCompactionEffectivenessTables(section, ui.textBody());
                    final var isLast = i == sections.size() - 1;
                    final var pointCount = section.requestSpeeds().size();
                    final var needsRedraw = isLast && (i != lastChartedAttemptIndex[0] || pointCount != lastChartedPointCount[0]);
                    if (!needsRedraw) continue;
                    JobDetailView.updateRequestSpeedChart(ui.speedChart(), section.requestSpeeds(), section.compactionsBeforeRequest(),
                            section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                    JobDetailView.updateRequestTimingChart(ui.timingChart(), section.requestSpeeds(), section.compactionsBeforeRequest(),
                            section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                    lastChartedAttemptIndex[0] = i;
                    lastChartedPointCount[0] = pointCount;
                }
            } catch (final Exception ex) {
                statsSummaryBody.removeAll();
                statsAttemptsBody.removeAll();
                attemptUis.clear();
                lastChartedAttemptIndex[0] = -1;
                lastChartedPointCount[0] = -1;
                statsSummaryBody.add(Panels.error(client.errorText(ex)));
            }
        };
        loadStatsOnSelect[0] = loadStats;
        refreshStatsButton.addClickListener(e -> loadStats.run());
        final Runnable loadAll = () -> {
            loadPrompt.run();
            loadDiff.run();
            loadHistory.run();
            loadJudge.run();
            // on demand: only while the operator is actually looking at the Stats tab, not on
            // every poll tick regardless of which tab is showing - see loadStatsOnSelect above.
            if (tabs.getSelectedTab() == statsTab) loadStats.run();
            if (promptSettled[0] && diffSettled[0] && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        };
        dialog.addOpenedChangeListener(e -> {
            if (!e.isOpened() && pollReg[0] != null) {
                pollReg[0].remove();
                pollReg[0] = null;
            }
        });
        buildRestartTab(client, jobId, task, currentJob, dialog, restartBody, onChanged);
        openTaskTranscriptTab(client, jobId, task, ownerView, dialog, transcriptBody);
        buildInterveneRow(client, jobId, task, interveneRow);
        dialog.open();
        loadAll.run();
        dialog.getUI().ifPresent(ui -> pollReg[0] = ui.addPollListener(e -> loadAll.run()));
    }

    /** #263/#321: a static form (built ONCE, never rebuilt by the polling loop above - unlike
     *  Prompt/Changes/History, rebuilding this would wipe out whatever the operator is mid-typing
     *  into the override fields). Always requires the job paused first - either flow writes
     *  jobs.task_overrides, and WorkerService only rebuilds cfg (so only re-reads that column)
     *  when a job is next claimed, so a write made while still running would silently not apply
     *  until a future pause/resume anyway.
     *
     *  Branches on whether the task has run yet: one that has gets the destructive restart flow
     *  (#263 - discards and redoes its own and every later task's work); one that hasn't gets the
     *  non-destructive #321 flow instead - same override fields, nothing discarded, no requeue()
     *  needed since the task was never going to run until the job resumes anyway. */
    private static void buildRestartTab(final ServiceClient client, final String jobId, final Api.PlanTask task,
            final Supplier<Api.Job> currentJob, final com.vaadin.flow.component.dialog.Dialog dialog,
            final Div restartBody, final Runnable onChanged) {
        final boolean pending = !task.implemented();
        final var job = currentJob.get();
        if (job == null || !"paused".equals(job.status())) {
            restartBody.add(Panels.mono(pending
                    ? "Pause the run first - a pending task's settings can only be set while the job is paused."
                    : "Pause the run first - a task can only be restarted while the job is paused."));
            return;
        }
        final var warning = new Span(pending
                ? "Sets " + task.id() + "'s own settings for when it runs - nothing has run yet, so nothing is discarded."
                : "Restarts " + task.id() + " and every task after it in plan order - their "
                + "current work is discarded (a backup git tag is made first in the workspace, but it is not "
                + "exposed here - recoverable only via a plain `git tag -l 'backup/*'` on the host).");
        warning.getStyle()
                .set("font-size", "0.85em").set("display", "block").set("margin-bottom", "8px");

        final var taskWall = new IntegerField("Task wall (sec)");
        final var taskTokens = new IntegerField("Task tokens");
        final var maxTurns = new IntegerField("Max turns");
        final var wrapupWall = new IntegerField("Wrap-up wall (sec)");
        final var wrapupTokens = new IntegerField("Wrap-up tokens");
        for (final var field : List.of(taskWall, taskTokens, maxTurns, wrapupWall, wrapupTokens)) {
            field.setPlaceholder("run default");
            field.setWidth("140px");
        }
        final var form = new HorizontalLayout(taskWall, taskTokens, maxTurns, wrapupWall, wrapupTokens);
        form.getStyle().set("flex-wrap", "wrap");

        // #417: compaction_trigger has been a legal restart-override key since #345 but never had
        // an editable field in this form at all - a pre-existing gap, cheap to close while already
        // touching this form. Grouped with compactionStrategies below rather than in the generic
        // `form` row above, since both configure the same "Context compaction" mechanism.
        final var compactionTrigger = new IntegerField("Compaction trigger");
        compactionTrigger.setMin(0);
        compactionTrigger.setPlaceholder("run default");
        compactionTrigger.setWidth("140px");

        // #417: which compaction strategy/strategies this task uses, and in what order - overrides
        // the run-level default (CompactionStrategyList's own getValue() is blank = "unset", which
        // setPendingTaskOverride's caller below already treats as "don't include this key").
        final var compactionStrategies = new CompactionStrategyList();
        final var compactionForm = new VerticalLayout(new Span("Context compaction"), compactionTrigger, compactionStrategies);
        compactionForm.setPadding(false);
        compactionForm.setSpacing(false);
        compactionForm.getStyle().set("margin-top", "4px");

        // #265: the mid-task judge - every judgeEveryNTurns turns, a separate LLM checks whether
        // this task is still on the most efficient path to its goal and will meet its deadline.
        // Restart-tunable so a task already stuck/drifting (the whole reason this exists) can be
        // caught on its very next attempt without waiting for a fresh run.
        final var judgeEnabled = new Checkbox("Mid-task judge");
        final var judgeEveryNTurns = new IntegerField("Every N turns");
        final var judgeModel = new ProviderModelPicker(client, "Judge model");
        judgeEveryNTurns.setPlaceholder("run default (10)");
        judgeEveryNTurns.setWidth("140px");
        final var judgeForm = new HorizontalLayout(judgeEnabled, judgeEveryNTurns, judgeModel);
        judgeForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #307: the budget-exhaustion rescue step - one extra dedicated session, with the harness's
        // own fresh verification output injected, before accepting a budget-exhausted (rc=3) task as
        // final. Only the enabled toggle is restart-overridable (same reasoning as the judge's own
        // wall/token fields staying run-level-only, see effectiveRescueCfg()'s own javadoc) - the
        // rescue session's budget/compaction knobs are an operator choice, not exposed here.
        final var rescueEnabled = new Checkbox("Budget-exhaustion rescue");
        final var rescueForm = new HorizontalLayout(rescueEnabled);
        rescueForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #332: a dedicated toggle, not a CompactionStrategies member - see Tooltips.LESSON_RECORDING_ENABLED
        final var lessonRecordingEnabled = new Checkbox("Cross-task lesson recording");
        final var lessonRecordingForm = new HorizontalLayout(lessonRecordingEnabled);
        lessonRecordingForm.getStyle().set("flex-wrap", "wrap").set("align-items", "center").set("margin-top", "4px");

        // #266: every field above used to always render with just a "run default" placeholder -
        // no way anywhere in the UI to see what override values (if any) are already in effect for
        // this task from a PREVIOUS restart (only a direct look at jobs.task_overrides in the raw
        // DB could tell an operator that). Pre-fill from the job's own currently-active override.
        final Map<String, Object> currentOverride = job.task_overrides() != null
                && job.task_overrides().get(task.id()) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (currentOverride.get("task_wall_sec") instanceof Number n) taskWall.setValue(n.intValue());
        if (currentOverride.get("task_tokens") instanceof Number n) taskTokens.setValue(n.intValue());
        if (currentOverride.get("max_turns") instanceof Number n) maxTurns.setValue(n.intValue());
        if (currentOverride.get("wrapup_wall_sec") instanceof Number n) wrapupWall.setValue(n.intValue());
        if (currentOverride.get("wrapup_tokens") instanceof Number n) wrapupTokens.setValue(n.intValue());
        if (currentOverride.get("compaction_trigger") instanceof Number n) compactionTrigger.setValue(n.intValue());
        if (currentOverride.get("compaction_strategies") instanceof String s && !s.isBlank())
            compactionStrategies.setValue(List.of(s.split(",")));
        if (Boolean.TRUE.equals(currentOverride.get("judge_enabled"))) judgeEnabled.setValue(true);
        if (currentOverride.get("judge_every_n_turns") instanceof Number n) judgeEveryNTurns.setValue(n.intValue());
        if (currentOverride.get("judge_model") instanceof String s && !s.isBlank()) judgeModel.setValue(s);
        if (Boolean.TRUE.equals(currentOverride.get("budget_rescue_enabled"))) rescueEnabled.setValue(true);
        if (Boolean.TRUE.equals(currentOverride.get("lesson_recording_enabled"))) lessonRecordingEnabled.setValue(true);

        final var restartButton = new Button(pending ? "Save settings for " + task.id() : "Restart from " + task.id(), e -> {
            final Map<String, Object> overrides = new LinkedHashMap<>();
            if (taskWall.getValue() != null) overrides.put("task_wall_sec", taskWall.getValue());
            if (taskTokens.getValue() != null) overrides.put("task_tokens", taskTokens.getValue());
            if (maxTurns.getValue() != null) overrides.put("max_turns", maxTurns.getValue());
            if (wrapupWall.getValue() != null) overrides.put("wrapup_wall_sec", wrapupWall.getValue());
            if (wrapupTokens.getValue() != null) overrides.put("wrapup_tokens", wrapupTokens.getValue());
            if (compactionTrigger.getValue() != null) overrides.put("compaction_trigger", compactionTrigger.getValue());
            if (!compactionStrategies.getValue().isEmpty()) overrides.put("compaction_strategies", String.join(",", compactionStrategies.getValue()));
            if (judgeEnabled.getValue()) overrides.put("judge_enabled", true);
            if (judgeEveryNTurns.getValue() != null) overrides.put("judge_every_n_turns", judgeEveryNTurns.getValue());
            if (judgeModel.getValue() != null && !judgeModel.getValue().isBlank()) overrides.put("judge_model", judgeModel.getValue());
            if (rescueEnabled.getValue()) overrides.put("budget_rescue_enabled", true);
            if (lessonRecordingEnabled.getValue()) overrides.put("lesson_recording_enabled", true);
            try {
                if (pending) {
                    client.setPendingTaskOverride(jobId, task.id(), overrides);
                } else {
                    client.restartTask(jobId, task.id(), overrides);
                    client.requeue(jobId);
                }
                onChanged.run();
                dialog.close();
            } catch (final Exception ex) {
                log.warn("could not {} task {} on job {}: {}", pending ? "set pending override for" : "restart", task.id(), jobId, ex.toString());
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        restartButton.getStyle().set("margin-top", "8px");
        restartBody.add(warning, form, compactionForm, judgeForm, rescueForm, lessonRecordingForm, restartButton);
    }

    /** #264: the sessionsGrid counterpart of this dialog - scoped to the three pre-task phases with
     *  no other way to be inspected (Definition/Plan/Parallel plan); any other row (a task id,
     *  INTEGRATION, a continuation - already covered by the Plan grid, or simply not restartable)
     *  still opens for Prompt/Changes, just with the backend's own graceful "nothing to show"
     *  response and no Restart tab. Deliberately simpler than this dialog: no History (these phases
     *  are each one-shot per run - no multi-attempt comparison need). (See JobSessionDialog.) */
    private static void openTaskTranscriptTab(final ServiceClient client, final String jobId, final Api.PlanTask task,
            final Component ownerView, final com.vaadin.flow.component.dialog.Dialog dialog, final Div transcriptBody) {
        final var buffer = new StringBuilder();
        final var stopped = new AtomicBoolean(false);
        transcriptBody.removeAll();
        transcriptBody.add(Panels.mono("(waiting for the task's own session to start...)"));

        final java.util.function.Consumer<SseEvent> onEvent = event -> {
            if (!"line".equals(event.payloadType())) return;
            final String text = event.data().path("text").asText("");
            if (text.isBlank()) return;
            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(text);
            if (buffer.length() > TRANSCRIPT_CLIENT_CAP_CHARS) buffer.delete(0, buffer.length() - TRANSCRIPT_CLIENT_CAP_CHARS);
            transcriptBody.removeAll();
            transcriptBody.add(Panels.mono(buffer.toString()));
            transcriptBody.getElement().executeJs("this.scrollTop = this.scrollHeight");
        };
        final Runnable onLost = () -> transcriptBody.add(Panels.error("Live connection lost - reopen this dialog to retry."));

        // #310: captured here, on the UI thread (this method only ever runs on it) - the loop's own
        // callbacks run on its background thread, where a plain getUI() call would read Vaadin's
        // internal attach state without the session lock. #578, found live: this used to be
        // dialog.getUI() - but this method runs BEFORE dialog.open() in open()'s own call sequence
        // below, and a Vaadin Dialog is not attached to the UI tree until open() actually runs
        // (confirmed against the library's own docs: "you don't need to add the dialog component
        // anywhere before opening it"), so dialog.getUI() here ALWAYS returned empty - the whole
        // transcript-streaming thread silently never started, for every task, every time, the Live
        // tab's placeholder text never advancing past "(waiting for the task's own session to
        // start...)" regardless of whether the task was running or already finished. ownerView (the
        // page itself) is already attached well before this ever runs, so its own getUI() resolves
        // correctly regardless of the dialog's own open/close state.
        final var ui = ownerView.getUI().orElse(null);
        if (ui == null) return; // not attached - nothing to stream into
        final var loop = new TaskTranscriptLoop(client, jobId, task.id(), stopped::get,
                event -> ui.access(() -> onEvent.accept(event)),
                () -> ui.access(onLost::run));
        final var thread = new Thread(loop, "job-" + jobId + "-task-" + task.id() + "-transcript");
        thread.setDaemon(true);
        thread.start();
        // #307: stopped on EITHER the dialog's own explicit close OR the whole owning view
        // detaching (navigation away, session/UI teardown) - the opened-change listener alone
        // misses the second case entirely, since a Dialog's overlay isn't torn down just because
        // the view that opened it navigates away, same reasoning the owning view's own stopSse()
        // already applies to the page-level SSE loop via addDetachListener. #311: an AtomicBoolean,
        // not a plain boolean[] - this flag is written here on the UI thread and read on the
        // loop's own background thread, with no other synchronization between the two.
        final var detachReg = ownerView.addDetachListener(e -> stopped.set(true));
        dialog.addOpenedChangeListener(e -> { if (!e.isOpened()) { stopped.set(true); detachReg.remove(); } });
    }

    /** The Live tab's own send box: lets an operator steer the task directly, through the exact
     *  same per-turn mechanism the mid-task Judge already uses (ServiceClient.sendIntervention() -
     *  BenchController.intervene() - RunBench.buildJudgeCheckpoint()'s own mailbox check), so it
     *  reaches the agent at its own next turn boundary - whatever that message was, it's
     *  delivered as a durable, timestamped entry in the task's own session transcript, visible
     *  right here once it lands. A static form, built once - never rebuilt by this dialog's own
     *  poll loop, same reasoning buildRestartTab()'s own javadoc gives for why ITS form is static
     *  (rebuilding would wipe out whatever the operator is mid-typing). No check here that the
     *  task is actually running: a message sent to one that isn't just sits unconsumed until
     *  either it starts or this task's own next attempt clears it - a quiet no-op, not an error. */
    private static void buildInterveneRow(final ServiceClient client, final String jobId, final Api.PlanTask task, final Div interveneRow) {
        // a single-line TextField, not a multi-line TextArea (found live, #568 follow-up): now that
        // this row sits in the footer beside Close rather than its own block in the content area,
        // a tall multi-line box made the footer visibly taller than every other dialog's own footer.
        // TextField shares the same default Lumo control height as Button with no extra sizing needed.
        final var text = new TextField();
        text.setPlaceholder("Steer the agent - delivered at its next turn");
        final var send = new Button("Send", e -> {
            final var value = text.getValue();
            if (value == null || value.isBlank()) return;
            try {
                client.sendIntervention(jobId, task.id(), value);
                text.clear();
                Notification.show("Sent - delivered at the agent's next turn.", 4000, Notification.Position.BOTTOM_END);
            } catch (final Exception ex) {
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        final var row = new HorizontalLayout(text, send);
        row.setWidthFull();
        // flex-grow, not text.setWidthFull(): a plain width:100% on a flex item sets its flex-basis
        // to 100% too, which fights the Send button for the same space and (depending on the
        // layout's own cross-axis sizing) can leave text either too small or wide enough to force
        // the whole row to overflow - flex-grow alone correctly claims exactly "the row's width
        // minus the Send button's own natural width", with no fixed width to fight.
        row.setFlexGrow(1, text);
        // #568 follow-up, found live: this 8px top margin dates from when this row sat right below
        // the transcript in the scrollable content area (visual separation from the text above it).
        // It now lives alone in the footer beside Close - the leftover margin just pushed the text
        // field and Send 4px lower than Close's own top edge instead of sitting flush with it.
        interveneRow.removeAll();
        interveneRow.add(row);
    }
}
