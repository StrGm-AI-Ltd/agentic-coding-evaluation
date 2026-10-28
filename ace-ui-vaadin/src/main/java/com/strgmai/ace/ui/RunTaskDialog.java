package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** #472/#475: the finished-run counterpart of JobDetailView.openTaskPromptDialog() - same
 *  Prompt/Changes/History/Judge/Stats(+Transcript) depth, reusing that class's own static chart/
 *  formatting helpers and DiffView.render(), but scoped to a run_id (not a job UUID) and loaded
 *  once rather than polled: a finished run's own data never changes, so there's no Restart tab
 *  (needs a pausable job), no Live/intervene box (needs an active session to steer), and no poll
 *  listener at all - just one fetch per tab, same as any other one-shot dialog in this app. */
final class RunTaskDialog {
    private RunTaskDialog() {
    }

    static void open(final ServiceClient client, final String runId, final String taskId) {
        final var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(taskId);
        dialog.setWidth("min(900px, 90vw)");
        dialog.setHeight("min(650px, 85vh)");

        final var promptBody = new Div();
        promptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        final var diffBody = new Div();
        diffBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        diffBody.setVisible(false);
        final var historyBody = new Div();
        historyBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        historyBody.setVisible(false);
        final var judgeBody = new Div();
        judgeBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        judgeBody.setVisible(false);
        final var statsBody = new Div();
        statsBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        statsBody.setVisible(false);
        final var transcriptBody = new Div();
        transcriptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        transcriptBody.setVisible(false);

        final var promptTab = new com.vaadin.flow.component.tabs.Tab("Prompt");
        final var changesTab = new com.vaadin.flow.component.tabs.Tab("Changes");
        final var historyTab = new com.vaadin.flow.component.tabs.Tab("History");
        final var judgeTab = new com.vaadin.flow.component.tabs.Tab("Judge");
        final var statsTab = new com.vaadin.flow.component.tabs.Tab("Stats");
        final var transcriptTab = new com.vaadin.flow.component.tabs.Tab("Transcript");
        final var tabs = new com.vaadin.flow.component.tabs.Tabs(promptTab, changesTab, historyTab, judgeTab, statsTab, transcriptTab);
        tabs.getStyle().set("width", "100%").set("box-sizing", "border-box");
        tabs.addSelectedChangeListener(e -> {
            promptBody.setVisible(tabs.getSelectedTab() == promptTab);
            diffBody.setVisible(tabs.getSelectedTab() == changesTab);
            historyBody.setVisible(tabs.getSelectedTab() == historyTab);
            judgeBody.setVisible(tabs.getSelectedTab() == judgeTab);
            statsBody.setVisible(tabs.getSelectedTab() == statsTab);
            transcriptBody.setVisible(tabs.getSelectedTab() == transcriptTab);
        });

        final var content = new VerticalLayout(tabs, promptBody, diffBody, historyBody, judgeBody, statsBody, transcriptBody);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));

        loadPrompt(client, runId, taskId, promptBody);
        loadDiff(client, runId, taskId, diffBody);
        loadHistory(client, runId, taskId, historyBody);
        loadJudge(client, runId, taskId, judgeBody);
        loadStats(client, runId, taskId, statsBody);
        loadTranscript(client, runId, taskId, transcriptBody);

        dialog.open();
    }

    private static void loadPrompt(final ServiceClient client, final String runId, final String taskId, final Div promptBody) {
        try {
            final var prompt = client.runTaskPrompt(runId, taskId);
            promptBody.add(new com.vaadin.flow.component.Html("<div class=\"md-body\">" + Markdown.toHtml(prompt.text()) + "</div>"));
        } catch (final Exception ex) {
            promptBody.add(Panels.error(client.errorText(ex)));
        }
    }

    private static void loadDiff(final ServiceClient client, final String runId, final String taskId, final Div diffBody) {
        try {
            final var diff = client.runTaskDiff(runId, taskId);
            diffBody.add(DiffView.render(diff));
        } catch (final Exception ex) {
            diffBody.add(Panels.error(client.errorText(ex)));
        }
    }

    private static void loadHistory(final ServiceClient client, final String runId, final String taskId, final Div historyBody) {
        try {
            final var attempts = client.runTaskAttempts(runId, taskId);
            if (attempts.isEmpty()) {
                historyBody.add(Panels.mono("(no attempts recorded)"));
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
            historyBody.add(Panels.error(client.errorText(ex)));
        }
    }

    private static void loadJudge(final ServiceClient client, final String runId, final String taskId, final Div judgeBody) {
        try {
            final var checkpoints = client.runTaskJudge(runId, taskId);
            if (checkpoints.isEmpty()) {
                judgeBody.add(Panels.mono("(no judge checkpoints - either the judge wasn't enabled for this task, "
                        + "or it never reached its first interval)"));
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
            judgeBody.add(Panels.error(client.errorText(ex)));
        }
    }

    private static void loadStats(final ServiceClient client, final String runId, final String taskId, final Div statsBody) {
        try {
            final var stats = client.runTaskStats(runId, taskId);
            if ("not_started".equals(stats.path("state").asText(null))) {
                statsBody.add(Panels.mono("(not started)"));
                return;
            }
            // liveWallTime is only read by taskStatsLines() in the not-done branch; a finished run's
            // own tasks are always "done" (see BenchController.taskStatsForRun()), so null is safe.
            for (final var line : JobDetailView.taskStatsLines(stats, null)) statsBody.add(Panels.mono(line));
            final var sections = JobDetailView.attemptSections(stats);
            final var multiAttempt = sections.size() > 1;
            for (int i = 0; i < sections.size(); i++) {
                final var section = sections.get(i);
                if (multiAttempt) {
                    // #517 (A): same card treatment as JobDetailView's own live task dialog
                    statsBody.add(Panels.card(section.header(), Panels.mono(section.body())));
                }
                JobDetailView.addCompactionEffectivenessTables(section, statsBody);
                // #517 (B): this dialog is a one-shot load (a finished, already-imported run - no
                // polling), so build-then-immediately-update is just the simplest way to reuse the
                // same build()/update() split JobDetailView's own live version needs for its
                // in-place redraws; no second update ever follows here.
                final var attemptLabel = multiAttempt ? " — Attempt " + (i + 1) : "";
                final var speedChart = JobDetailView.buildRequestSpeedChart();
                JobDetailView.updateRequestSpeedChart(speedChart, section.requestSpeeds(), section.compactionsBeforeRequest(),
                        section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                final var timingChart = JobDetailView.buildRequestTimingChart();
                JobDetailView.updateRequestTimingChart(timingChart, section.requestSpeeds(), section.compactionsBeforeRequest(),
                        section.judgeOnTrackRequests(), section.judgeOffTrackRequests());
                statsBody.add(Panels.card("Prefill speed by request" + attemptLabel, speedChart),
                        Panels.card("Request timing" + attemptLabel, timingChart));
            }
        } catch (final Exception ex) {
            statsBody.add(Panels.error(client.errorText(ex)));
        }
    }

    private static void loadTranscript(final ServiceClient client, final String runId, final String taskId, final Div transcriptBody) {
        try {
            final var text = client.runTaskTranscript(runId, taskId).text();
            transcriptBody.add(text == null || text.isBlank()
                    ? Panels.mono("(no session transcript recorded for this task)") : Panels.mono(text));
        } catch (final Exception ex) {
            transcriptBody.add(Panels.error(client.errorText(ex)));
        }
    }
}
