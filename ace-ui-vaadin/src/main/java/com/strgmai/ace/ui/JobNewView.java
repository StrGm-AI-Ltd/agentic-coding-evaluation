package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New job form — the Vaadin twin of /jobs/new in the service UI: every RunSpec flag
 * (queue.py) that run_bench.py accepts, plus queue priority. Field values are
 * normalized by JobSpecs (unit-tested) and submitted to POST /api/jobs.
 */
@Route(value = "jobs/new", layout = MainLayout.class)
@CssImport("./styles/budget-field-helper.css")
public class JobNewView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(JobNewView.class);

    private final ServiceClient client;

    private final ComboBox<String> task = new ComboBox<>("task");
    private final ComboBox<String> model = new ComboBox<>("model");
    private final ComboBox<String> reviewerModel = new ComboBox<>("reviewer_model");
    private final ComboBox<String> trajectoryReviewerModel = new ComboBox<>("trajectory_reviewer_model");
    private final Select<String> harness = new Select<>();
    private final Select<String> mode = new Select<>();
    private final Select<String> planSource = new Select<>();
    private final Select<String> parallelPlan = new Select<>();
    private final Select<String> trajectoryUse = new Select<>();
    private final Select<String> reasoningEffort = new Select<>();
    private final MultiSelectComboBox<String> phases = new MultiSelectComboBox<>("phases");
    private final TextField parallel = new TextField("parallel");
    private final TextField javaHome = new TextField("java_home");
    private final TextField runIdField = new TextField("run_id");
    private final IntegerField taskWall = new IntegerField("task_wall");
    private final IntegerField taskTokens = new IntegerField("task_tokens");
    private final IntegerField implWall = new IntegerField("impl_wall");
    private final IntegerField implTokens = new IntegerField("impl_tokens");
    private final IntegerField planTokens = new IntegerField("plan_tokens");
    private final IntegerField contextWindow = new IntegerField("context_window");
    private final IntegerField firstTokenTimeout = new IntegerField("first_token_timeout");
    private final IntegerField compactionTrigger = new IntegerField("compaction_trigger");
    private final IntegerField reviewWallSec = new IntegerField("review_wall_sec");
    private final IntegerField wallBudget = new IntegerField("wall_budget");
    private final IntegerField priority = new IntegerField("priority");
    private final NumberField parallelWeight = new NumberField("parallel_weight");
    private final NumberField reviewWeight = new NumberField("review_weight");
    private final NumberField trajectoryWeight = new NumberField("trajectory_weight");
    private final NumberField temperature = new NumberField("temperature");
    private final NumberField topP = new NumberField("top_p");
    private final IntegerField topK = new IntegerField("top_k");
    private final NumberField repetitionPenalty = new NumberField("repetition_penalty");
    private final IntegerField maxTokens = new IntegerField("max_tokens");
    private final IntegerField parallelPlanWall = new IntegerField("parallel_plan_wall");
    private final IntegerField handoffWall = new IntegerField("handoff_wall");
    private final IntegerField wrapupWall = new IntegerField("wrapup_wall");
    private final Checkbox handoffNotes = new Checkbox("handoff_notes");
    private final Checkbox systemRules = new Checkbox("system_rules");
    private final Checkbox selfReview = new Checkbox("self_review");
    private final Checkbox reviewBlind = new Checkbox("review_blind");
    private final Checkbox trajectoryReview = new Checkbox("trajectory_review");
    private final Checkbox noContextProbe = new Checkbox("no_context_probe");
    private final Checkbox contextProbeFresh = new Checkbox("context_probe_fresh");
    private final Checkbox keepWorkspace = new Checkbox("keep_workspace");
    private final Checkbox manageDocker = new Checkbox("manage_docker");
    private final Checkbox skipDocker = new Checkbox("skip_docker");
    private final VerticalLayout errors = new VerticalLayout();
    // populated once in loadSuggestions(); looked up by name as the operator picks a rung, so
    // picking a task shows what it actually tests before the job is ever enqueued
    private List<Api.RungDetail> rungDetails = List.of();
    private final com.vaadin.flow.component.html.Div taskInfo = Panels.mono("");

    public JobNewView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        task.setAllowCustomValue(true);
        task.setRequired(true);
        task.setPlaceholder("L1…L7 rung");
        // JobSpecs.build's only possible failure is "task is required" - clear the inline error
        // the moment the operator picks something, rather than making them resubmit to see it go
        task.addValueChangeListener(e -> task.setInvalid(false));
        task.addValueChangeListener(e -> renderTaskInfo(e.getValue()));
        taskInfo.setVisible(false);
        for (final var picker : List.of(model, reviewerModel, trajectoryReviewerModel)) {
            picker.setAllowCustomValue(true);
        }
        reviewerModel.setPlaceholder("provider/model — openrouter/…, gpt-5, claude-opus-5…");
        trajectoryReviewerModel.setPlaceholder(reviewerModel.getPlaceholder());

        configureSelect(harness, "", "ref", "pi");
        configureSelect(mode, "", "monolithic", "orchestrated");
        configureSelect(planSource, "", "agent", "reference");
        configureSelect(parallelPlan, "", "on", "off");
        configureSelect(trajectoryUse, "", "calibration", "direct");
        configureSelect(reasoningEffort, "", "none", "low", "medium", "high");
        harness.setLabel("harness");
        mode.setLabel("mode");
        planSource.setLabel("plan_source");
        parallelPlan.setLabel("parallel_plan");
        trajectoryUse.setLabel("trajectory_use");
        reasoningEffort.setLabel("reasoning_effort");
        reasoningEffort.setItemLabelGenerator(v -> v.isEmpty() ? "blank = default per phase" : v.equals("none") ? "none (disable thinking)" : v);

        // the ids RunSpec.PHASES/RunBench.PHASES accept - L7_full_platform's and L3p_point_in_time's
        // own multi-phase sequence; blank/none selected = every phase the rung defines (the default)
        phases.setItems("p0_definition", "p1_plan", "p2_implementation");
        phases.setPlaceholder("blank = every phase the rung defines");
        parallel.setPlaceholder("auto, or 1–99");
        manageDocker.setValue(true);
        priority.setValue(0);
        taskWall.setMin(1);
        taskTokens.setMin(1);
        implWall.setMin(1);
        implTokens.setMin(1);
        planTokens.setMin(1);
        contextWindow.setMin(1);
        firstTokenTimeout.setMin(1);
        firstTokenTimeout.setPlaceholder("blank = default (180s)");
        // #208: compactionTrigger's 2-line helper text makes it taller than firstTokenTimeout
        // (placeholder only, no helper) - Forms.row()'s bottom alignment then pushes
        // firstTokenTimeout's label down to match, instead of the two staying level. Reserving
        // the same helper-text height on both (see budget-field-helper.css) fixes that.
        firstTokenTimeout.addClassName("reserve-helper-height");
        compactionTrigger.addClassName("reserve-helper-height");
        compactionTrigger.setMin(0);
        compactionTrigger.setHelperText("blank = default (28000), 0 = disabled");
        reviewWallSec.setMin(1);
        reviewWallSec.setPlaceholder("blank = default (900s)");
        wallBudget.setMin(1);
        parallelWeight.setMin(0);
        parallelWeight.setMax(1);
        reviewWeight.setMin(0);
        reviewWeight.setMax(1);
        trajectoryWeight.setMin(0);
        trajectoryWeight.setMax(1);
        temperature.setMin(0);
        temperature.setPlaceholder("blank = operator default");
        topP.setMin(0);
        topP.setMax(1);
        topP.setPlaceholder("blank = operator default");
        topK.setMin(1);
        topK.setPlaceholder("blank = model default");
        repetitionPenalty.setMin(0);
        repetitionPenalty.setPlaceholder("blank = model default");
        maxTokens.setMin(1);
        maxTokens.setPlaceholder("blank = context-probe-derived cap");
        parallelPlanWall.setMin(1);
        parallelPlanWall.setPlaceholder("blank = default (600s)");
        parallelPlanWall.addClassName("reserve-helper-height");
        handoffWall.setMin(1);
        handoffWall.setPlaceholder("blank = default (300s)");
        handoffWall.addClassName("reserve-helper-height");
        wrapupWall.setMin(1);
        wrapupWall.setHelperText("blank = default (300s, before the decode-speed scale)");
        wrapupWall.addClassName("reserve-helper-height");

        add(new H2("New job"));
        add(new RouterLink("← Queue", JobsView.class));
        errors.setPadding(false);
        add(errors);

        add(Forms.section("Run",
                Forms.row(task, runIdField, harness, mode, planSource),
                Forms.row(taskInfo),
                Forms.row(phases, parallel, parallelPlan, parallelWeight)));
        add(Forms.section("Budgets",
                Forms.row(taskWall, taskTokens, implWall, implTokens, planTokens, wallBudget, contextWindow),
                Forms.row(firstTokenTimeout, compactionTrigger),
                Forms.row(parallelPlanWall, handoffWall, wrapupWall)));
        add(Forms.section("Sampler",
                Forms.row(temperature, topP, topK, repetitionPenalty),
                Forms.row(maxTokens, reasoningEffort)));
        add(Forms.section("Reviewers",
                Forms.row(reviewerModel, reviewWeight, reviewBlind),
                Forms.row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse, trajectoryReview),
                Forms.row(reviewWallSec)));
        add(Forms.section("Model & flags",
                Forms.row(model, handoffNotes, systemRules, selfReview),
                Forms.row(javaHome, noContextProbe, contextProbeFresh, keepWorkspace),
                Forms.row(manageDocker, skipDocker, priority)));

        final var submit = new Button("Enqueue job", e -> submit());
        submit.getStyle().set("margin-top", "12px");
        add(submit);

        addAttachListener(e -> loadSuggestions());
    }

    private static void configureSelect(final Select<String> select, final String... options) {
        select.setItems(options);
        select.setValue(options[0]);
        select.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);
    }

    private void loadSuggestions() {
        try {
            final var runs = client.runs(null, null, null, null, null);
            // every rung tasks/ladder.json declares, union'd with any task name a real run already
            // used (a legacy/custom value not currently in the ladder must never silently vanish) -
            // a DB-only list used to hide every rung this dataset had never happened to run yet
            final var tasks = new java.util.TreeSet<String>(client.tasks());
            tasks.addAll(Links.distinctRuns(runs, Api.Run::task));
            task.setItems(tasks);
            model.setItems(Links.distinctRuns(runs, Api.Run::model));
            reviewerModel.setItems(ExperimentNewView.reviewerSuggestions());
            trajectoryReviewerModel.setItems(ExperimentNewView.reviewerSuggestions());
            rungDetails = client.taskDetails();
            renderTaskInfo(task.getValue());
        } catch (final Exception e) {
            log.warn("could not load task/model suggestions: {}", e.toString());
        }
    }

    /** Shows the selected rung's own description/budget/denominator/checks (each with its own
     *  category/weight/description from CheckId) - what picking a task actually commits the job
     *  to, before it's ever enqueued. Hidden when nothing is selected or the rung is unrecognized
     *  (a free-typed custom value - task.setAllowCustomValue(true) - has no ladder entry to show). */
    private void renderTaskInfo(final String selected) {
        final var detail = rungDetails.stream().filter(r -> r.name().equals(selected)).findFirst();
        if (selected == null || selected.isBlank() || detail.isEmpty()) {
            taskInfo.setVisible(false);
            return;
        }
        final var r = detail.get();
        final var lines = new StringBuilder();
        lines.append(r.name()).append(" — ").append(r.description()).append('\n');
        lines.append("budget ").append(Fmt.duration(r.budget_sec() == null ? null : r.budget_sec().doubleValue()))
                .append(" · denominator ").append(r.denominator()).append('\n');
        lines.append("checks:\n");
        for (final var c : r.checks())
            lines.append("  ").append(c.check_id()).append(" (").append(c.category()).append(", weight ").append(c.weight())
                    .append("): ").append(c.description()).append('\n');
        taskInfo.setText(lines.toString().stripTrailing());
        taskInfo.setVisible(true);
    }

    /** Collects the raw field values by RunSpec key, exactly as the server's own form does. */
    private Map<String, Object> rawValues() {
        // returned as Map<String, Object>; empty-diamond under var would infer <Object, Object>
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("model", model.getValue());
        raw.put("harness", harness.getValue());
        raw.put("mode", mode.getValue());
        raw.put("plan_source", planSource.getValue());
        raw.put("reasoning_effort", reasoningEffort.getValue());
        raw.put("phases", String.join(",", phases.getValue()));
        raw.put("temperature", temperature.getValue());
        raw.put("top_p", topP.getValue());
        raw.put("top_k", topK.getValue());
        raw.put("repetition_penalty", repetitionPenalty.getValue());
        raw.put("max_tokens", maxTokens.getValue());
        raw.put("parallel_plan_wall", parallelPlanWall.getValue());
        raw.put("handoff_wall", handoffWall.getValue());
        raw.put("wrapup_wall", wrapupWall.getValue());
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("impl_wall", implWall.getValue());
        raw.put("impl_tokens", implTokens.getValue());
        raw.put("plan_tokens", planTokens.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("first_token_timeout", firstTokenTimeout.getValue());
        raw.put("compaction_trigger", compactionTrigger.getValue());
        raw.put("parallel", parallel.getValue());
        raw.put("parallel_plan", parallelPlan.getValue());
        raw.put("parallel_weight", parallelWeight.getValue());
        raw.put("reviewer_model", reviewerModel.getValue());
        raw.put("review_weight", reviewWeight.getValue());
        raw.put("trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        raw.put("trajectory_weight", trajectoryWeight.getValue());
        raw.put("trajectory_use", trajectoryUse.getValue());
        raw.put("review_wall_sec", reviewWallSec.getValue());
        raw.put("java_home", javaHome.getValue());
        raw.put("wall_budget", wallBudget.getValue());
        raw.put("run_id", runIdField.getValue());
        raw.put("handoff_notes", handoffNotes.getValue());
        raw.put("system_rules", systemRules.getValue());
        raw.put("self_review", selfReview.getValue());
        raw.put("review_blind", reviewBlind.getValue());
        raw.put("trajectory_review", trajectoryReview.getValue());
        raw.put("no_context_probe", noContextProbe.getValue());
        raw.put("context_probe_fresh", contextProbeFresh.getValue());
        raw.put("keep_workspace", keepWorkspace.getValue());
        raw.put("manage_docker", manageDocker.getValue());
        raw.put("skip_docker", skipDocker.getValue());
        return raw;
    }

    private void submit() {
        errors.removeAll();
        task.setInvalid(false);
        final Map<String, Object> spec;   // assigned exactly once below; a legal blank final
        try {
            spec = JobSpecs.build(task.getValue(), rawValues());
        } catch (final IllegalArgumentException e) {
            // JobSpecs.build's only validation is "task is required" - a red field + inline
            // message right on the ComboBox, not a round-trip through a generic panel
            task.setInvalid(true);
            task.setErrorMessage(e.getMessage());
            return;
        }
        try {
            final var job = client.enqueueJob(spec, priority.getValue() == null ? 0 : priority.getValue());
            Notification.show("Queued job #" + job.id() + " for " + job.run_id(),
                    4000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.navigate("jobs"));
        } catch (final Exception e) {
            log.warn("could not enqueue job for task {}: {}", task.getValue(), e.toString());
            errors.add(Panels.error(client.errorText(e)));
        }
    }
}
