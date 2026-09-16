package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
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
    private final TextField reasoning = new TextField("reasoning");
    private final TextField phases = new TextField("phases");
    private final TextField parallel = new TextField("parallel");
    private final TextField javaHome = new TextField("java_home");
    private final TextField config = new TextField("config");
    private final TextField runIdField = new TextField("run_id");
    private final IntegerField taskWall = new IntegerField("task_wall");
    private final IntegerField taskTokens = new IntegerField("task_tokens");
    private final IntegerField implWall = new IntegerField("impl_wall");
    private final IntegerField implTokens = new IntegerField("impl_tokens");
    private final IntegerField planTokens = new IntegerField("plan_tokens");
    private final IntegerField contextWindow = new IntegerField("context_window");
    private final IntegerField wallBudget = new IntegerField("wall_budget");
    private final IntegerField priority = new IntegerField("priority");
    private final NumberField parallelWeight = new NumberField("parallel_weight");
    private final NumberField reviewWeight = new NumberField("review_weight");
    private final NumberField trajectoryWeight = new NumberField("trajectory_weight");
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

    public JobNewView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        task.setAllowCustomValue(true);
        task.setRequired(true);
        task.setPlaceholder("L1…L7 rung");
        for (ComboBox<String> picker : List.of(model, reviewerModel, trajectoryReviewerModel)) {
            picker.setAllowCustomValue(true);
        }
        reviewerModel.setPlaceholder("provider/model — openrouter/…, gpt-5, claude-opus-5…");
        trajectoryReviewerModel.setPlaceholder(reviewerModel.getPlaceholder());

        configureSelect(harness, "", "ref", "pi");
        configureSelect(mode, "", "monolithic", "orchestrated");
        configureSelect(planSource, "", "agent", "reference");
        configureSelect(parallelPlan, "", "on", "off");
        configureSelect(trajectoryUse, "", "calibration", "direct");
        harness.setLabel("harness");
        mode.setLabel("mode");
        planSource.setLabel("plan_source");
        parallelPlan.setLabel("parallel_plan");
        trajectoryUse.setLabel("trajectory_use");

        reasoning.setPlaceholder("default=high (or step=low,review=high)");
        phases.setPlaceholder("definition,plan,t1,t2 (comma list)");
        parallel.setPlaceholder("auto, or 2–99");
        manageDocker.setValue(true);
        priority.setValue(0);
        taskWall.setMin(1);
        taskTokens.setMin(1);
        implWall.setMin(1);
        implTokens.setMin(1);
        planTokens.setMin(1);
        contextWindow.setMin(1);
        wallBudget.setMin(1);
        parallelWeight.setMin(0);
        parallelWeight.setMax(1);
        reviewWeight.setMin(0);
        reviewWeight.setMax(1);
        trajectoryWeight.setMin(0);
        trajectoryWeight.setMax(1);

        add(new H2("New job"));
        add(new RouterLink("← Queue", JobsView.class));
        errors.setPadding(false);
        add(errors);

        add(Forms.section("Run",
                Forms.row(task, runIdField, harness, mode, planSource),
                Forms.row(reasoning, phases, parallel, parallelPlan, parallelWeight)));
        add(Forms.section("Budgets",
                Forms.row(taskWall, taskTokens, implWall, implTokens, planTokens, wallBudget, contextWindow)));
        add(Forms.section("Reviewers",
                Forms.row(reviewerModel, reviewWeight, reviewBlind),
                Forms.row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse, trajectoryReview)));
        add(Forms.section("Model & flags",
                Forms.row(model, handoffNotes, systemRules, selfReview),
                Forms.row(javaHome, config, noContextProbe, contextProbeFresh, keepWorkspace),
                Forms.row(manageDocker, skipDocker, priority)));

        Button submit = new Button("Enqueue job", e -> submit());
        submit.getStyle().set("margin-top", "12px");
        add(submit);

        addAttachListener(e -> loadSuggestions());
    }

    private static void configureSelect(Select<String> select, String... options) {
        select.setItems(options);
        select.setValue(options[0]);
        select.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);
    }

    private void loadSuggestions() {
        try {
            List<Api.Run> runs = client.runs(null, null, null, null, null);
            task.setItems(Links.distinctRuns(runs, Api.Run::task));
            model.setItems(Links.distinctRuns(runs, Api.Run::model));
        } catch (Exception ignored) {
            // suggestions are optional; the server still validates
        }
    }

    /** Collects the raw field values by RunSpec key, exactly as the server's own form does. */
    private Map<String, Object> rawValues() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("model", model.getValue());
        raw.put("harness", harness.getValue());
        raw.put("mode", mode.getValue());
        raw.put("plan_source", planSource.getValue());
        raw.put("reasoning", reasoning.getValue());
        raw.put("phases", phases.getValue());
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("impl_wall", implWall.getValue());
        raw.put("impl_tokens", implTokens.getValue());
        raw.put("plan_tokens", planTokens.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("parallel", parallel.getValue());
        raw.put("parallel_plan", parallelPlan.getValue());
        raw.put("parallel_weight", parallelWeight.getValue());
        raw.put("reviewer_model", reviewerModel.getValue());
        raw.put("review_weight", reviewWeight.getValue());
        raw.put("trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        raw.put("trajectory_weight", trajectoryWeight.getValue());
        raw.put("trajectory_use", trajectoryUse.getValue());
        raw.put("java_home", javaHome.getValue());
        raw.put("config", config.getValue());
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
        Map<String, Object> spec;
        try {
            spec = JobSpecs.build(task.getValue(), rawValues());
        } catch (IllegalArgumentException e) {
            errors.add(Panels.error(e.getMessage()));
            return;
        }
        try {
            Api.Job job = client.enqueueJob(spec, priority.getValue() == null ? 0 : priority.getValue());
            Notification.show("Queued job #" + job.id() + " for " + job.run_id(),
                    4000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.navigate("jobs"));
        } catch (Exception e) {
            errors.add(Panels.error(client.errorText(e)));
        }
    }
}
