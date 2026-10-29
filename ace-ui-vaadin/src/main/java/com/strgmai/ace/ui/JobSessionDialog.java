package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Extracted from JobDetailView (Phase 1 of closing the live-monitoring gap on the new main Job
 *  detail page - see the plan this followed). The Sessions grid's own per-row dialog: Prompt/Changes,
 *  plus a Restart tab for the three pre-task phases (Definition/Plan/Parallel plan) that have one.
 *  {@code currentJob} is a Supplier, not a snapshot, since the dialog can stay open across several
 *  poll cycles and the Restart tab's own paused-check needs to see the job's CURRENT state, not
 *  whatever it was the moment the dialog opened. */
final class JobSessionDialog {
    private static final Logger log = LoggerFactory.getLogger(JobSessionDialog.class);

    private JobSessionDialog() {
    }

    static void open(final ServiceClient client, final String jobId, final Supplier<Api.Job> currentJob,
            final JobLiveState.SessionRow row, final Runnable onChanged) {
        final String name = row.name();
        final var restartablePhases = Set.of("p0_definition", "p1_plan", "PARALLEL_PLAN");
        final var dialog = new com.vaadin.flow.component.dialog.Dialog();
        dialog.setHeaderTitle(row.description() == null || row.description().isBlank() ? "Session" : row.description());
        dialog.setWidth("min(900px, 90vw)");
        dialog.setHeight("min(650px, 85vh)");

        // same explicit width fix the task dialog needs - see its own comment for why
        final var promptBody = new Div();
        promptBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        final var diffBody = new Div();
        diffBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        diffBody.setVisible(false);
        final var restartBody = new Div();
        restartBody.getStyle().set("overflow", "auto").set("width", "100%").set("box-sizing", "border-box");
        restartBody.setVisible(false);

        final var promptTab = new Tab("Prompt");
        final var changesTab = new Tab("Changes");
        final boolean showRestart = name != null && restartablePhases.contains(name);
        final var restartTab = showRestart ? new Tab("Restart") : null;
        final Tabs tabs = showRestart
                ? new Tabs(promptTab, changesTab, restartTab)
                : new Tabs(promptTab, changesTab);
        tabs.getStyle().set("width", "100%").set("box-sizing", "border-box");
        tabs.addSelectedChangeListener(e -> {
            promptBody.setVisible(tabs.getSelectedTab() == promptTab);
            diffBody.setVisible(tabs.getSelectedTab() == changesTab);
            restartBody.setVisible(restartTab != null && tabs.getSelectedTab() == restartTab);
        });

        final var content = new VerticalLayout(tabs, promptBody, diffBody, restartBody);
        content.setPadding(false);
        content.setSpacing(false);
        content.setSizeFull();
        dialog.add(content);
        dialog.getFooter().add(new Button("Close", e -> dialog.close()));

        final Registration[] pollReg = new Registration[1];
        final boolean[] promptSettled = {false};
        final boolean[] diffSettled = {false};

        final Runnable loadPrompt = () -> {
            try {
                final var prompt = name == null ? new Api.TaskPrompt(false, "") : client.sessionPrompt(jobId, name);
                promptBody.removeAll();
                if (!prompt.started()) {
                    final var note = new Span(name == null || !restartablePhases.contains(name)
                            ? "There is no detail view for this session - only Definition, Plan, and Parallel "
                              + "plan are inspectable here (a task's own row has the same information via the "
                              + "plan table above instead)."
                            : "This phase hasn't started yet - the fixed instruction below is all there is "
                              + "until it does. This updates automatically once it does.");
                    note.getStyle().set("font-size", "0.85em")
                            .set("display", "block").set("margin-bottom", "8px");
                    promptBody.add(note);
                    promptSettled[0] = name == null || !restartablePhases.contains(name);
                } else {
                    promptSettled[0] = true;
                }
                if (!prompt.text().isBlank())
                    promptBody.add(new com.vaadin.flow.component.Html("<div class=\"md-body\">" + Markdown.toHtml(prompt.text()) + "</div>"));
            } catch (final Exception ex) {
                promptBody.removeAll();
                promptBody.add(Panels.error(client.errorText(ex)));
                promptSettled[0] = true;
            }
        };
        final Runnable loadDiff = () -> {
            try {
                final var diff = name == null ? new Api.TaskDiff(false, false, "", "", "", false, List.of(), false, null)
                        : client.sessionDiff(jobId, name);
                diffBody.removeAll();
                diffBody.add(DiffView.render(diff));
                diffSettled[0] = diff.finished();
            } catch (final Exception ex) {
                diffBody.removeAll();
                diffBody.add(Panels.error(client.errorText(ex)));
                diffSettled[0] = true;
            }
        };
        final Runnable loadAll = () -> {
            loadPrompt.run();
            loadDiff.run();
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
        if (showRestart) buildSessionRestartTab(client, jobId, currentJob, name, dialog, restartBody, onChanged);
        dialog.open();
        loadAll.run();
        dialog.getUI().ifPresent(ui -> pollReg[0] = ui.addPollListener(e -> loadAll.run()));
    }

    /** #264: the phase-session counterpart of the task dialog's own Restart tab - same static-form-
     *  built-once rule (never rebuilt by the polling loop, so mid-typed override values survive),
     *  same paused gate. No wrap-up fields: p0/p1/PARALLEL_PLAN have no wrap-up concept at all. */
    private static void buildSessionRestartTab(final ServiceClient client, final String jobId, final Supplier<Api.Job> currentJob,
            final String name, final com.vaadin.flow.component.dialog.Dialog dialog, final Div restartBody, final Runnable onChanged) {
        final var job = currentJob.get();
        if (job == null || !"paused".equals(job.status())) {
            restartBody.add(Panels.mono("Pause the run first - a session can only be restarted while the job is paused."));
            return;
        }
        final var warning = new Span("Restarts " + name + " and every later phase/task - their current work is "
                + "discarded (a backup git tag is made first in the workspace, but it is not exposed here - "
                + "recoverable only via a plain `git tag -l 'backup/*'` on the host).");
        warning.getStyle().set("font-size", "0.85em").set("display", "block").set("margin-bottom", "8px");

        final var taskWall = new IntegerField("Task wall (sec)");
        final var taskTokens = new IntegerField("Task tokens");
        final var maxTurns = new IntegerField("Max turns");
        final var firstTokenTimeout = new IntegerField("First-token timeout (sec)");
        for (final var field : List.of(taskWall, taskTokens, maxTurns, firstTokenTimeout)) {
            field.setPlaceholder("run default");
            field.setWidth("160px");
        }
        final var form = new HorizontalLayout(taskWall, taskTokens, maxTurns, firstTokenTimeout);
        form.getStyle().set("flex-wrap", "wrap");

        // #398: task granularity only has meaning for "p1_plan" - restarting p0_definition/
        // PARALLEL_PLAN with it set would be inert (neither phase reads it), so the field is only
        // shown for a p1_plan restart, same "don't offer a control that silently does nothing"
        // reasoning as the compaction fields' own task-vs-phase scoping.
        final var taskGranularity = new Select<String>();
        taskGranularity.setLabel("Task granularity");
        taskGranularity.setItems("", "fine", "medium", "coarse");
        taskGranularity.setItemLabelGenerator(v -> v.isEmpty() ? "—" : v);
        taskGranularity.setWidth("160px");
        taskGranularity.setTooltipText(Tooltips.TASK_GRANULARITY);

        // #266: same pre-fill as the task dialog's own Restart tab - restartSession() stores this
        // phase's own override in the SAME jobs.task_overrides map, just keyed by phase name
        // instead of task id (see BenchController.restartSession()/doRestart()).
        final Map<String, Object> currentOverride = job.task_overrides() != null
                && job.task_overrides().get(name) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (currentOverride.get("task_wall_sec") instanceof Number n) taskWall.setValue(n.intValue());
        if (currentOverride.get("task_tokens") instanceof Number n) taskTokens.setValue(n.intValue());
        if (currentOverride.get("max_turns") instanceof Number n) maxTurns.setValue(n.intValue());
        if (currentOverride.get("first_token_timeout_sec") instanceof Number n) firstTokenTimeout.setValue(n.intValue());
        if (currentOverride.get("task_granularity") instanceof String s && !s.isBlank()) taskGranularity.setValue(s);

        final var restartButton = new Button("Restart from " + name, e -> {
            final Map<String, Object> overrides = new LinkedHashMap<>();
            if (taskWall.getValue() != null) overrides.put("task_wall_sec", taskWall.getValue());
            if (taskTokens.getValue() != null) overrides.put("task_tokens", taskTokens.getValue());
            if (maxTurns.getValue() != null) overrides.put("max_turns", maxTurns.getValue());
            if (firstTokenTimeout.getValue() != null) overrides.put("first_token_timeout_sec", firstTokenTimeout.getValue());
            if ("p1_plan".equals(name) && taskGranularity.getValue() != null && !taskGranularity.getValue().isBlank())
                overrides.put("task_granularity", taskGranularity.getValue());
            try {
                client.restartSession(jobId, name, overrides);
                client.requeue(jobId);
                onChanged.run();
                dialog.close();
            } catch (final Exception ex) {
                log.warn("could not restart session {} on job {}: {}", name, jobId, ex.toString());
                Notification.show(client.errorText(ex), 6000, Notification.Position.BOTTOM_END);
            }
        });
        restartButton.getStyle().set("margin-top", "8px");
        restartBody.add(warning, form);
        if ("p1_plan".equals(name)) restartBody.add(taskGranularity);
        restartBody.add(restartButton);
    }
}
