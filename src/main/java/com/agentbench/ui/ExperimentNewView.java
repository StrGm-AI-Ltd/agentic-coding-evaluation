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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New experiment form — the Vaadin twin of /experiments/new in the service UI.
 * Templates: harness_effect (orch vs mono arms), model_ab (model A vs B),
 * agent_ab (reference agent vs Pi). Submits POST /api/experiments.
 */
@Route(value = "experiments/new", layout = MainLayout.class)
public class ExperimentNewView extends VerticalLayout {

    /** The curated reviewer-model ids from experiments.py REVIEWER_MODELS, as provider/id. */
    private static final List<String> REVIEWER_SUGGESTIONS = List.of(
            "anthropic/claude-opus-5", "anthropic/claude-sonnet-5", "anthropic/claude-haiku-4-5-20251001",
            "anthropic/claude-fable-5-1", "openai/gpt-5", "nebius/Nemotron-3-Ultra-550b-a55b");

    private final transient ServiceClient client;

    private final TextField name = new TextField("name");
    private final Select<String> template = new Select<>();
    private final IntegerField k = new IntegerField("k (repeats per arm)");
    private final IntegerField taskWall = new IntegerField("task_wall (seconds, per task)");
    private final TextField taskTokens = new TextField("task_tokens");
    private final IntegerField contextWindow = new IntegerField("context_window");
    private final Checkbox noContextProbe = new Checkbox("no_context_probe (derive the step-0 window from harness config)");
    private final ComboBox<String> model = modelPicker("model");
    private final ComboBox<String> modelA = modelPicker("model_a");
    private final ComboBox<String> modelB = modelPicker("model_b");
    private final ComboBox<String> reviewerModel = modelPicker("reviewer_model");
    private final NumberField reviewWeight = new NumberField("review_weight");
    private final Checkbox reviewBlind = new Checkbox("review_blind (hide PROGRESS claims from the code reviewer)");
    private final ComboBox<String> trajectoryReviewerModel = modelPicker("trajectory_reviewer_model");
    private final NumberField trajectoryWeight = new NumberField("trajectory_weight");
    private final Select<String> trajectoryUse = new Select<>();
    private final Checkbox trajectoryReview = new Checkbox("trajectory_review");
    private final Checkbox orch = new Checkbox("orch — orchestrated");
    private final Checkbox mono = new Checkbox("mono — monolithic");
    private final Checkbox monoRules = new Checkbox("mono+rules — monolithic with prompt-only rules");
    private final Checkbox par = new Checkbox("par — parallel impl");
    private final IntegerField parallel = new IntegerField("parallel (par arm)");
    private final Select<String> agentMode = new Select<>();
    private final Select<String> agentA = new Select<>();
    private final Select<String> agentB = new Select<>();
    private final VerticalLayout errors = new VerticalLayout();

    private final VerticalLayout harnessEffectSection = new VerticalLayout();
    private final VerticalLayout modelAbSection = new VerticalLayout();
    private final VerticalLayout agentAbSection = new VerticalLayout();

    public ExperimentNewView(ServiceClient client) {
        this.client = client;
        setPadding(true);

        template.setLabel("template");
        template.setItems("harness_effect", "model_ab", "agent_ab");
        template.setItemLabelGenerator(ExperimentNewView::templateLabel);
        template.setValue("harness_effect");
        k.setValue(3);
        k.setMin(1);
        k.setMax(20);
        taskWall.setValue(3600);
        taskWall.setMin(1);
        taskTokens.setValue("auto");
        taskTokens.setPlaceholder("auto, or a number");
        contextWindow.setMin(1);
        contextWindow.setPlaceholder("blank = probe");
        name.setPlaceholder("optional, defaults to template + timestamp");
        parallel.setValue(3);
        parallel.setMin(2);
        parallel.setMax(20);
        reviewWeight.setMin(0);
        reviewWeight.setMax(1);
        trajectoryWeight.setMin(0);
        trajectoryWeight.setMax(1);
        agentMode.setLabel("mode");
        agentMode.setItems("orchestrated", "monolithic");
        agentMode.setValue("orchestrated");
        agentA.setLabel("agent A");
        agentB.setLabel("agent B");
        for (Select<String> agent : List.of(agentA, agentB)) {
            agent.setItems("ref", "pi");
        }
        agentA.setValue("ref");
        agentB.setValue("pi");
        configureUseSelect();

        orch.setValue(true);
        mono.setValue(true);

        add(new H2("New experiment"));
        add(new RouterLink("← Experiments", ExperimentsView.class));
        errors.setPadding(false);
        add(errors);

        add(section("Experiment",
                row(name, template, k),
                row(taskWall, taskTokens, contextWindow, noContextProbe)));

        harnessEffectSection.setPadding(false);
        harnessEffectSection.setSpacing(false);
        harnessEffectSection.add(section("Arms (harness_effect)",
                row(model, orch, mono, monoRules, par),
                row(parallel)));
        modelAbSection.setPadding(false);
        modelAbSection.setSpacing(false);
        modelAbSection.add(section("Models (model_ab)",
                row(modelA, modelB)));
        agentAbSection.setPadding(false);
        agentAbSection.setSpacing(false);
        agentAbSection.add(section("Agents (agent_ab)",
                row(model, agentMode, agentA, agentB)));
        add(harnessEffectSection, modelAbSection, agentAbSection);

        add(section("Reviewers (all templates)",
                row(reviewerModel, reviewWeight, reviewBlind, trajectoryReview),
                row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse)));

        updateVisibility();
        template.addValueChangeListener(e -> updateVisibility());
        par.addValueChangeListener(e -> parallel.setEnabled(par.getValue()));

        Button submit = new Button("Enqueue experiment", e -> submit());
        submit.getStyle().set("margin-top", "12px");
        add(submit);

        addAttachListener(e -> loadSuggestions());
    }

    private void configureUseSelect() {
        trajectoryUse.setLabel("trajectory_use");
        trajectoryUse.setItems("", "calibration", "direct");
        trajectoryUse.setValue("");
        trajectoryUse.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);
    }

    private static String templateLabel(String template) {
        return switch (template) {
            case "model_ab" -> "model_ab — model A vs model B, same harness/budgets";
            case "agent_ab" -> "agent_ab — reference agent vs Pi, same model/budgets";
            default -> "harness_effect — orchestrated vs monolithic (vs parallel, vs prompt-only rules)";
        };
    }

    private static ComboBox<String> modelPicker(String label) {
        ComboBox<String> picker = new ComboBox<>(label);
        picker.setAllowCustomValue(true);
        if (label.startsWith("reviewer") || label.startsWith("trajectory")) {
            picker.setPlaceholder("provider/model — " + REVIEWER_SUGGESTIONS.get(0) + "…");
        } else {
            picker.setPlaceholder("local model id, or pick a seen model");
        }
        return picker;
    }

    private void updateVisibility() {
        String value = template.getValue();
        harnessEffectSection.setVisible("harness_effect".equals(value));
        modelAbSection.setVisible("model_ab".equals(value));
        agentAbSection.setVisible("agent_ab".equals(value));
    }

    private void loadSuggestions() {
        try {
            List<Api.Run> runs = client.runs(null, null, null, null, null);
            List<String> models = RunsView.distinct(runs, Api.Run::model);
            model.setItems(models);
            modelA.setItems(models);
            modelB.setItems(models);
            reviewerModel.setItems(REVIEWER_SUGGESTIONS);
            trajectoryReviewerModel.setItems(REVIEWER_SUGGESTIONS);
        } catch (Exception ignored) {
            // suggestions are optional; the server still validates
        }
    }

    private void submit() {
        errors.removeAll();
        Map<String, Object> params = new LinkedHashMap<>();
        String currentTemplate = template.getValue();

        String requiredModel;
        if ("model_ab".equals(currentTemplate)) {
            requiredModel = modelA.getValue() != null && !modelA.getValue().isBlank()
                    && modelB.getValue() != null && !modelB.getValue().isBlank() ? modelA.getValue() : "";
        } else {
            requiredModel = model.getValue();
        }
        if (requiredModel == null || requiredModel.isBlank()) {
            errors.add(Panels.error("a model is required for every template"));
            return;
        }

        params.put("task_wall", taskWall.getValue() == null ? 3600 : taskWall.getValue());
        Object tokens = taskTokens.getValue();
        params.put("task_tokens", tokens != null && tokens.toString().matches("\\d+") && !tokens.toString().isBlank()
                ? Integer.valueOf(tokens.toString()) : "auto");
        if (contextWindow.getValue() != null) {
            params.put("context_window", contextWindow.getValue());
        }
        params.put("no_context_probe", noContextProbe.getValue());
        params.put("reviewer_model", blankToNull(reviewerModel.getValue()));
        if (reviewWeight.getValue() != null) {
            params.put("review_weight", reviewWeight.getValue());
        }
        params.put("review_blind", reviewBlind.getValue());
        params.put("trajectory_reviewer_model", blankToNull(trajectoryReviewerModel.getValue()));
        if (trajectoryWeight.getValue() != null) {
            params.put("trajectory_weight", trajectoryWeight.getValue());
        }
        if (!trajectoryUse.getValue().isEmpty()) {
            params.put("trajectory_use", trajectoryUse.getValue());
        }

        switch (currentTemplate) {
            case "harness_effect" -> {
                params.put("model", model.getValue());
                List<String> arms = new ArrayList<>();
                if (orch.getValue()) arms.add("orch");
                if (mono.getValue()) arms.add("mono");
                if (monoRules.getValue()) arms.add("mono+rules");
                if (par.getValue()) arms.add("par");
                if (arms.isEmpty()) {
                    errors.add(Panels.error("pick at least one arm"));
                    return;
                }
                params.put("arms", arms);
                params.put("parallel", parallel.getValue() == null ? 3 : parallel.getValue());
            }
            case "model_ab" -> {
                params.put("model_a", modelA.getValue());
                params.put("model_b", modelB.getValue());
            }
            default -> {
                params.put("model", model.getValue());
                params.put("mode", agentMode.getValue());
                params.put("agents", List.of(agentA.getValue(), agentB.getValue()));
            }
        }

        String experimentName = name.getValue() == null || name.getValue().isBlank()
                ? currentTemplate + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                : name.getValue();

        try {
            Api.Experiment experiment = client.createExperiment(experimentName, currentTemplate, params,
                    k.getValue() == null ? 3 : k.getValue());
            Notification.show("Queued experiment #" + experiment.id() + " with "
                    + (experiment.jobs() == null ? 0 : experiment.jobs().size()) + " jobs",
                    4000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.navigate("experiments/" + experiment.id()));
        } catch (Exception e) {
            errors.add(Panels.error(client.errorText(e)));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
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
}
