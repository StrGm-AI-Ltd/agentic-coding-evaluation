package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
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
 * (queue.py) that run_bench.py accepts, plus queue priority. Submits POST /api/jobs.
 */
@Route(value = "jobs/new", layout = MainLayout.class)
public class JobNewView extends VerticalLayout {

    private final transient ServiceClient client;

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

        add(section("Run",
                row(task, runIdField, harness, mode, planSource),
                row(reasoning, phases, parallel, parallelPlan, parallelWeight)));
        add(section("Budgets",
                row(taskWall, taskTokens, implWall, implTokens, planTokens, wallBudget, contextWindow)));
        add(section("Reviewers",
                row(reviewerModel, reviewWeight, reviewBlind),
                row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse, trajectoryReview)));
        add(section("Model & flags",
                row(model, handoffNotes, systemRules, selfReview),
                row(javaHome, config, noContextProbe, contextProbeFresh, keepWorkspace),
                row(manageDocker, skipDocker, priority)));

        Button submit = new Button("Enqueue job", e -> submit());
        submit.getStyle().set("margin-top", "12px");
        add(submit);

        addAttachListener(e -> loadSuggestions());
    }

    private static Select<String> configureSelect(Select<String> select, String... options) {
        select.setItems(options);
        select.setValue(options[0]);
        select.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);
        return select;
    }

    private VerticalLayout section(String title, HorizontalLayout... rows) {
        H4 header = new H4(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        VerticalLayout sectionLayout = new VerticalLayout(header);
        sectionLayout.setPadding(false);
        sectionLayout.setSpacing(false);
        for (HorizontalLayout row : rows) {
            sectionLayout.add(row);
        }
        return sectionLayout;
    }

    private static HorizontalLayout row(com.vaadin.flow.component.Component... fields) {
        HorizontalLayout layout = new HorizontalLayout(fields);
        layout.getStyle().set("flex-wrap", "wrap");
        layout.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        layout.setSpacing(true);
        return layout;
    }

    private void loadSuggestions() {
        try {
            List<Api.Run> runs = client.runs(null, null, null, null, null);
            task.setItems(RunsView.distinct(runs, Api.Run::task));
            model.setItems(RunsView.distinct(runs, Api.Run::model));
        } catch (Exception ignored) {
            // suggestions are optional; the server still validates
        }
    }

    private void submit() {
        errors.removeAll();
        if (task.getValue() == null || task.getValue().isBlank()) {
            errors.add(Panels.error("task is required (a rung from tasks/ladder.json)"));
            return;
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("task", task.getValue());
        put(spec, "model", model.getValue());
        put(spec, "harness", harness.getValue());
        put(spec, "mode", mode.getValue());
        put(spec, "plan_source", planSource.getValue());
        put(spec, "reasoning", reasoning.getValue());
        put(spec, "phases", phases.getValue());
        put(spec, "task_wall", taskWall.getValue());
        put(spec, "task_tokens", taskTokens.getValue());
        put(spec, "impl_wall", implWall.getValue());
        put(spec, "impl_tokens", implTokens.getValue());
        put(spec, "plan_tokens", planTokens.getValue());
        put(spec, "context_window", contextWindow.getValue());
        put(spec, "parallel", parallel.getValue());
        put(spec, "parallel_plan", parallelPlan.getValue());
        put(spec, "parallel_weight", parallelWeight.getValue());
        put(spec, "reviewer_model", reviewerModel.getValue());
        put(spec, "review_weight", reviewWeight.getValue());
        put(spec, "trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        put(spec, "trajectory_weight", trajectoryWeight.getValue());
        put(spec, "trajectory_use", trajectoryUse.getValue());
        put(spec, "java_home", javaHome.getValue());
        put(spec, "config", config.getValue());
        put(spec, "wall_budget", wallBudget.getValue());
        put(spec, "run_id", runIdField.getValue());
        spec.put("handoff_notes", handoffNotes.getValue());
        spec.put("system_rules", systemRules.getValue());
        spec.put("self_review", selfReview.getValue());
        spec.put("review_blind", reviewBlind.getValue());
        spec.put("trajectory_review", trajectoryReview.getValue());
        spec.put("no_context_probe", noContextProbe.getValue());
        spec.put("context_probe_fresh", contextProbeFresh.getValue());
        spec.put("keep_workspace", keepWorkspace.getValue());
        spec.put("manage_docker", manageDocker.getValue());
        spec.put("skip_docker", skipDocker.getValue());

        try {
            Api.Job job = client.enqueueJob(spec, priority.getValue() == null ? 0 : priority.getValue());
            Notification.show("Queued job #" + job.id() + " for " + job.run_id(),
                    4000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.navigate("jobs"));
        } catch (Exception e) {
            errors.add(Panels.error(client.errorText(e)));
        }
    }

    private static void put(Map<String, Object> spec, String key, Object value) {
        if (value != null && (!(value instanceof String text) || !text.isBlank())) {
            spec.put(key, value);
        }
    }
}
