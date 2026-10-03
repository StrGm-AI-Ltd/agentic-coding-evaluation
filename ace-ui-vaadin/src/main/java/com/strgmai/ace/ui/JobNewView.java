package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
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
public class JobNewView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(JobNewView.class);

    private final ServiceClient client;

    private final ComboBox<String> task = new ComboBox<>("Task");
    private final ComboBox<String> model = new ComboBox<>("Model");
    private final ComboBox<String> reviewerModel = new ComboBox<>("Reviewer model");
    private final ComboBox<String> trajectoryReviewerModel = new ComboBox<>("Trajectory reviewer model");
    private final Select<String> harness = new Select<>();
    private final Select<String> mode = new Select<>();
    private final Select<String> planSource = new Select<>();
    private final Select<String> parallelPlan = new Select<>();
    private final Select<String> trajectoryUse = new Select<>();
    private final Select<String> reasoningEffort = new Select<>();
    private final MultiSelectComboBox<String> phases = new MultiSelectComboBox<>("Phases");
    private final TextField parallel = new TextField("Parallel tasks");
    private final TextField javaHome = new TextField("Java home");
    private final TextField runIdField = new TextField("Run ID");
    private final IntegerField taskWall = new IntegerField("Task wall-clock budget");
    private final IntegerField taskTokens = new IntegerField("Task token budget");
    private final IntegerField implWall = new IntegerField("Implementation wall-clock budget");
    private final IntegerField implTokens = new IntegerField("Implementation token budget");
    private final IntegerField planWall = new IntegerField("Plan wall-clock budget");
    private final IntegerField planTokens = new IntegerField("Plan token budget");
    private final IntegerField contextWindow = new IntegerField("Context window");
    private final IntegerField firstTokenTimeout = new IntegerField("First-token timeout");
    private final IntegerField compactionTrigger = new IntegerField("Compaction trigger");
    private final IntegerField reviewWallSec = new IntegerField("Review wall-clock budget");
    private final IntegerField reviewTokens = new IntegerField("Review token budget");
    private final IntegerField maxTurns = new IntegerField("Max turns");
    private final IntegerField fixWall = new IntegerField("Fix-up wall-clock budget");
    private final IntegerField fixTokens = new IntegerField("Fix-up token budget");
    private final IntegerField parallelPlanTokens = new IntegerField("Parallel-plan token budget");
    private final IntegerField handoffTokens = new IntegerField("Handoff token budget");
    private final IntegerField wrapupTokens = new IntegerField("Wrap-up token budget");
    private final IntegerField dockerMemoryMib = new IntegerField("Docker memory cap (MiB)");
    private final IntegerField wallBudget = new IntegerField("Wall budget override (smoke test)");
    private final IntegerField priority = new IntegerField("Priority");
    private final NumberField parallelWeight = new NumberField("Parallelization weight");
    private final NumberField efficiencyWeight = new NumberField("Efficiency weight");
    private final NumberField reviewWeight = new NumberField("Review weight");
    private final NumberField trajectoryWeight = new NumberField("Trajectory weight");
    private final NumberField temperature = new NumberField("Temperature");
    private final NumberField topP = new NumberField("Top-p");
    private final IntegerField topK = new IntegerField("Top-k");
    private final NumberField repetitionPenalty = new NumberField("Repetition penalty");
    private final IntegerField maxTokens = new IntegerField("Max tokens");
    private final IntegerField parallelPlanWall = new IntegerField("Parallel-plan wall-clock budget");
    private final IntegerField handoffWall = new IntegerField("Handoff wall-clock budget");
    private final IntegerField wrapupWall = new IntegerField("Wrap-up wall-clock budget");
    private final Checkbox handoffNotes = new Checkbox("Handoff notes");
    private final Checkbox systemRules = new Checkbox("System rules");
    private final Checkbox selfReview = new Checkbox("Self-review");
    private final Checkbox reviewBlind = new Checkbox("Blind review");
    private final Checkbox trajectoryReview = new Checkbox("Trajectory review");
    private final Checkbox noContextProbe = new Checkbox("Skip context probe");
    private final Checkbox contextProbeFresh = new Checkbox("Fresh context probe");
    private final Checkbox keepWorkspace = new Checkbox("Keep workspace");
    private final Checkbox manageDocker = new Checkbox("Manage Docker");
    private final Checkbox skipDocker = new Checkbox("Skip Docker");
    private final Checkbox dockerKeepWarm = new Checkbox("Keep Docker warm");
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
        task.setTooltipText(Tooltips.TASK);
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
        model.setTooltipText(Tooltips.MODEL);
        reviewerModel.setTooltipText(Tooltips.REVIEWER_MODEL);
        trajectoryReviewerModel.setTooltipText(Tooltips.TRAJECTORY_REVIEWER_MODEL);
        runIdField.setPlaceholder("blank = auto-generated");
        runIdField.setTooltipText(Tooltips.RUN_ID);

        configureSelect(harness, "", "ref", "pi");
        configureSelect(mode, "", "monolithic", "orchestrated");
        configureSelect(planSource, "", "agent", "reference");
        configureSelect(parallelPlan, "", "on", "off");
        configureSelect(trajectoryUse, "", "calibration", "direct");
        configureSelect(reasoningEffort, "", "none", "low", "medium", "high");
        harness.setLabel("Harness");
        mode.setLabel("Mode");
        planSource.setLabel("Plan source");
        parallelPlan.setLabel("Parallel planning");
        trajectoryUse.setLabel("Trajectory use");
        reasoningEffort.setLabel("Reasoning effort");
        reasoningEffort.setItemLabelGenerator(v -> v.isEmpty() ? "blank = default per phase" : v.equals("none") ? "none (disable thinking)" : v);
        harness.setTooltipText(Tooltips.HARNESS);
        mode.setTooltipText(Tooltips.MODE);
        planSource.setTooltipText(Tooltips.PLAN_SOURCE);
        parallelPlan.setTooltipText(Tooltips.PARALLEL_PLAN);
        trajectoryUse.setTooltipText(Tooltips.TRAJECTORY_USE);
        reasoningEffort.setTooltipText(Tooltips.REASONING_EFFORT);

        // the ids RunSpec.PHASES/RunBench.PHASES accept - L7_full_platform's and L3p_point_in_time's
        // own multi-phase sequence; blank/none selected = every phase the rung defines (the default)
        phases.setItems("p0_definition", "p1_plan", "p2_implementation");
        phases.setPlaceholder("blank = every phase the rung defines");
        phases.setTooltipText(Tooltips.PHASES);
        parallel.setPlaceholder("auto, or 1–99");
        parallel.setTooltipText(Tooltips.PARALLEL);
        manageDocker.setValue(true);
        priority.setValue(0);
        priority.setTooltipText(Tooltips.PRIORITY);
        taskWall.setMin(1);
        taskWall.setPlaceholder("blank = derived from the rung's budget");
        taskWall.setTooltipText(Tooltips.TASK_WALL);
        taskTokens.setMin(1);
        taskTokens.setPlaceholder("blank = derived from the rung's budget");
        taskTokens.setTooltipText(Tooltips.TASK_TOKENS);
        implWall.setMin(1);
        implWall.setPlaceholder("blank = rung's derived budget");
        implWall.setTooltipText(Tooltips.IMPL_WALL);
        implTokens.setMin(1);
        implTokens.setPlaceholder("blank = operator default");
        implTokens.setTooltipText(Tooltips.IMPL_TOKENS);
        planWall.setMin(0);
        planWall.setPlaceholder("blank = default (900s), 0 = unlimited");
        planWall.setTooltipText(Tooltips.PLAN_WALL);
        planTokens.setMin(1);
        planTokens.setPlaceholder("blank = operator default");
        planTokens.setTooltipText(Tooltips.PLAN_TOKENS);
        contextWindow.setMin(1);
        contextWindow.setTooltipText(Tooltips.CONTEXT_WINDOW);
        firstTokenTimeout.setMin(1);
        firstTokenTimeout.setPlaceholder("blank = default (180s)");
        firstTokenTimeout.setTooltipText(Tooltips.FIRST_TOKEN_TIMEOUT);
        compactionTrigger.setMin(0);
        compactionTrigger.setPlaceholder("blank = default (28000), 0 = disabled");
        compactionTrigger.setTooltipText(Tooltips.COMPACTION_TRIGGER);
        reviewWallSec.setMin(1);
        reviewWallSec.setPlaceholder("blank = default (900s)");
        reviewWallSec.setTooltipText(Tooltips.REVIEW_WALL_SEC);
        reviewTokens.setMin(0);
        reviewTokens.setPlaceholder("blank = unlimited");
        reviewTokens.setTooltipText(Tooltips.REVIEW_TOKENS);
        maxTurns.setMin(1);
        maxTurns.setPlaceholder("blank = default (400)");
        maxTurns.setTooltipText(Tooltips.MAX_TURNS);
        fixWall.setMin(0);
        fixWall.setPlaceholder("blank = unlimited");
        fixWall.setTooltipText(Tooltips.FIX_WALL);
        fixTokens.setMin(0);
        fixTokens.setPlaceholder("blank = unlimited");
        fixTokens.setTooltipText(Tooltips.FIX_TOKENS);
        parallelPlanTokens.setMin(0);
        parallelPlanTokens.setPlaceholder("blank = unlimited");
        parallelPlanTokens.setTooltipText(Tooltips.PARALLEL_PLAN_TOKENS);
        handoffTokens.setMin(0);
        handoffTokens.setPlaceholder("blank = unlimited");
        handoffTokens.setTooltipText(Tooltips.HANDOFF_TOKENS);
        wrapupTokens.setMin(0);
        wrapupTokens.setPlaceholder("blank = unlimited");
        wrapupTokens.setTooltipText(Tooltips.WRAPUP_TOKENS);
        dockerMemoryMib.setMin(1);
        dockerMemoryMib.setPlaceholder("blank = default (4096 MiB)");
        dockerMemoryMib.setTooltipText(Tooltips.DOCKER_MEMORY_MIB);
        dockerKeepWarm.setTooltipText(Tooltips.DOCKER_KEEP_WARM);
        wallBudget.setMin(1);
        wallBudget.setPlaceholder("blank = not used (normal budget applies)");
        wallBudget.setTooltipText(Tooltips.WALL_BUDGET);
        parallelWeight.setMin(0);
        parallelWeight.setMax(1);
        parallelWeight.setPlaceholder("blank = default (0.1)");
        parallelWeight.setTooltipText(Tooltips.PARALLEL_WEIGHT);
        efficiencyWeight.setMin(0);
        efficiencyWeight.setMax(1);
        efficiencyWeight.setPlaceholder("blank = 0 (opt-in; not blended by default)");
        efficiencyWeight.setTooltipText(Tooltips.EFFICIENCY_WEIGHT);
        reviewWeight.setMin(0);
        reviewWeight.setMax(1);
        reviewWeight.setPlaceholder("blank = default (0.1)");
        reviewWeight.setTooltipText(Tooltips.REVIEW_WEIGHT);
        trajectoryWeight.setMin(0);
        trajectoryWeight.setMax(1);
        trajectoryWeight.setPlaceholder("blank = default (0.1)");
        trajectoryWeight.setTooltipText(Tooltips.TRAJECTORY_WEIGHT);
        temperature.setMin(0);
        temperature.setPlaceholder("blank = operator default");
        temperature.setTooltipText(Tooltips.TEMPERATURE);
        topP.setMin(0);
        topP.setMax(1);
        topP.setPlaceholder("blank = operator default");
        topP.setTooltipText(Tooltips.TOP_P);
        topK.setMin(1);
        topK.setPlaceholder("blank = model default");
        topK.setTooltipText(Tooltips.TOP_K);
        repetitionPenalty.setMin(0);
        repetitionPenalty.setPlaceholder("blank = model default");
        repetitionPenalty.setTooltipText(Tooltips.REPETITION_PENALTY);
        maxTokens.setMin(1);
        maxTokens.setPlaceholder("blank = context-probe-derived cap");
        maxTokens.setTooltipText(Tooltips.MAX_TOKENS);
        parallelPlanWall.setMin(1);
        parallelPlanWall.setPlaceholder("blank = default (600s)");
        parallelPlanWall.setTooltipText(Tooltips.PARALLEL_PLAN_WALL);
        handoffWall.setMin(1);
        handoffWall.setPlaceholder("blank = default (300s)");
        handoffWall.setTooltipText(Tooltips.HANDOFF_WALL);
        wrapupWall.setMin(1);
        wrapupWall.setPlaceholder("blank = default (300s, scaled)");
        wrapupWall.setTooltipText(Tooltips.WRAPUP_WALL);
        javaHome.setPlaceholder("blank = operator/machine default");
        javaHome.setTooltipText(Tooltips.JAVA_HOME);
        handoffNotes.setTooltipText(Tooltips.HANDOFF_NOTES);
        systemRules.setTooltipText(Tooltips.SYSTEM_RULES);
        selfReview.setTooltipText(Tooltips.SELF_REVIEW);
        reviewBlind.setTooltipText(Tooltips.REVIEW_BLIND);
        trajectoryReview.setTooltipText(Tooltips.TRAJECTORY_REVIEW);
        noContextProbe.setTooltipText(Tooltips.NO_CONTEXT_PROBE);
        contextProbeFresh.setTooltipText(Tooltips.CONTEXT_PROBE_FRESH);
        keepWorkspace.setTooltipText(Tooltips.KEEP_WORKSPACE);
        manageDocker.setTooltipText(Tooltips.MANAGE_DOCKER);
        skipDocker.setTooltipText(Tooltips.SKIP_DOCKER);

        add(new H2("New job"));
        add(new RouterLink("← Queue", JobsView.class));
        errors.setPadding(false);
        add(errors);

        add(Forms.section("Run",
                Forms.row(task, runIdField, harness, mode, planSource),
                Forms.row(taskInfo),
                Forms.row(phases, parallel, parallelPlan, parallelWeight)));
        add(Forms.section("Budgets",
                Forms.row(taskWall, taskTokens, implWall, implTokens, planWall, planTokens, wallBudget, contextWindow),
                Forms.row(firstTokenTimeout, compactionTrigger, maxTurns),
                Forms.row(parallelPlanWall, handoffWall, wrapupWall),
                Forms.row(parallelPlanTokens, handoffTokens, wrapupTokens),
                Forms.row(fixWall, fixTokens),
                Forms.row(efficiencyWeight)));
        add(Forms.section("Sampler",
                Forms.row(temperature, topP, topK, repetitionPenalty),
                Forms.row(maxTokens, reasoningEffort)));
        add(Forms.section("Reviewers",
                Forms.row(reviewerModel, reviewWeight, reviewBlind),
                Forms.row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse, trajectoryReview),
                Forms.row(reviewWallSec, reviewTokens)));
        add(Forms.section("Model & flags",
                Forms.row(model, handoffNotes, systemRules, selfReview),
                Forms.row(javaHome, noContextProbe, contextProbeFresh, keepWorkspace),
                Forms.row(manageDocker, skipDocker, dockerMemoryMib, dockerKeepWarm, priority)));

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
        try {
            // the model server's own list, merged in on top of past-run models (GET /api/models) -
            // a DB-only list used to hide every model this dataset had never happened to run yet,
            // same fix as #225 applied to task - a cold backend with zero run history still gets a
            // useful picker, not an empty one
            final var live = client.models();
            final var merged = java.util.stream.Stream.concat(model.getGenericDataView().getItems(), live.stream())
                    .distinct().sorted().toList();
            model.setItems(merged);
        } catch (final Exception e) {
            log.warn("could not reach the model server for live model suggestions: {}", e.toString());
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
        raw.put("parallel_plan_tokens", parallelPlanTokens.getValue());
        raw.put("handoff_tokens", handoffTokens.getValue());
        raw.put("wrapup_tokens", wrapupTokens.getValue());
        raw.put("max_turns", maxTurns.getValue());
        raw.put("fix_wall", fixWall.getValue());
        raw.put("fix_tokens", fixTokens.getValue());
        raw.put("docker_memory_mib", dockerMemoryMib.getValue());
        raw.put("docker_keep_warm", dockerKeepWarm.getValue());
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("impl_wall", implWall.getValue());
        raw.put("impl_tokens", implTokens.getValue());
        raw.put("plan_wall", planWall.getValue());
        raw.put("plan_tokens", planTokens.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("first_token_timeout", firstTokenTimeout.getValue());
        raw.put("compaction_trigger", compactionTrigger.getValue());
        raw.put("parallel", parallel.getValue());
        raw.put("parallel_plan", parallelPlan.getValue());
        raw.put("parallel_weight", parallelWeight.getValue());
        raw.put("efficiency_weight", efficiencyWeight.getValue());
        raw.put("reviewer_model", reviewerModel.getValue());
        raw.put("review_weight", reviewWeight.getValue());
        raw.put("trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        raw.put("trajectory_weight", trajectoryWeight.getValue());
        raw.put("trajectory_use", trajectoryUse.getValue());
        raw.put("review_wall_sec", reviewWallSec.getValue());
        raw.put("review_tokens", reviewTokens.getValue());
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
