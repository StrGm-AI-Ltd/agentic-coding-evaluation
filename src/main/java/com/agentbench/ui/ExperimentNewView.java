package com.agentbench.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
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
 * agent_ab (reference agent vs Pi). Params are normalized by ExperimentParams
 * (unit-tested) and submitted to POST /api/experiments.
 */
@Route(value = "experiments/new", layout = MainLayout.class)
public class ExperimentNewView extends VerticalLayout {


    private final ServiceClient client;

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
    private final NumberField reviewWeight = weightField("review_weight");
    private final Checkbox reviewBlind = new Checkbox("review_blind (hide PROGRESS claims from the code reviewer)");
    private final ComboBox<String> trajectoryReviewerModel = modelPicker("trajectory_reviewer_model");
    private final NumberField trajectoryWeight = weightField("trajectory_weight");
    private final Select<String> trajectoryUse = new Select<>();
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

    private static NumberField weightField(String label) {
        NumberField field = new NumberField(label);
        field.setMin(0);
        field.setMax(1);
        return field;
    }

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
        trajectoryUse.setLabel("trajectory_use");
        trajectoryUse.setItems("", "calibration", "direct");
        trajectoryUse.setValue("");
        trajectoryUse.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);

        orch.setValue(true);
        mono.setValue(true);

        add(new H2("New experiment"));
        add(new RouterLink("← Experiments", ExperimentsView.class));
        errors.setPadding(false);
        add(errors);

        add(Forms.section("Experiment",
                Forms.row(name, template, k, model),
                Forms.row(taskWall, taskTokens, contextWindow, noContextProbe)));

        // NB: Vaadin components have exactly ONE parent — a shared field must live in
        // one section only (this bug shipped the model picker away from harness_effect
        // when it was added to two sections).
        harnessEffectSection.setPadding(false);
        harnessEffectSection.setSpacing(false);
        harnessEffectSection.add(Forms.section("Arms (harness_effect)",
                Forms.row(orch, mono, monoRules, par),
                Forms.row(parallel)));
        modelAbSection.setPadding(false);
        modelAbSection.setSpacing(false);
        modelAbSection.add(Forms.section("Models (model_ab)",
                Forms.row(modelA, modelB)));
        agentAbSection.setPadding(false);
        agentAbSection.setSpacing(false);
        agentAbSection.add(Forms.section("Agents (agent_ab)",
                Forms.row(agentMode, agentA, agentB)));
        add(harnessEffectSection, modelAbSection, agentAbSection);

        add(Forms.section("Reviewers (all templates)",
                Forms.row(reviewerModel, reviewWeight, reviewBlind),
                Forms.row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse)));

        updateVisibility();
        template.addValueChangeListener(e -> updateVisibility());
        par.addValueChangeListener(e -> parallel.setEnabled(par.getValue()));

        Button submit = new Button("Enqueue experiment", e -> submit());
        submit.getStyle().set("margin-top", "12px");
        add(submit);

        addAttachListener(e -> loadSuggestions());
    }

    /**
     * The curated reviewer ids from experiments.py REVIEWER_MODELS, as provider/id.
     * Nebius ids are org-prefixed (verified against the real API — bare ids 404).
     */
    static List<String> reviewerSuggestions() {
        return List.of(
                "anthropic/claude-opus-5", "anthropic/claude-sonnet-5", "anthropic/claude-haiku-4-5-20251001",
                "anthropic/claude-fable-5-1", "openai/gpt-5",
                "nebius/nvidia/Nemotron-3-Ultra-550b-a55b", "nebius/zai-org/GLM-5.3");
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
            picker.setPlaceholder("provider/model — " + reviewerSuggestions().get(0) + "…");
        } else {
            picker.setPlaceholder("local model id, or pick a seen model");
        }
        return picker;
    }

    private void updateVisibility() {
        String value = template.getValue();
        model.setVisible(modelPickerVisible(value)); // shared by harness_effect and agent_ab
        harnessEffectSection.setVisible("harness_effect".equals(value));
        modelAbSection.setVisible("model_ab".equals(value));
        agentAbSection.setVisible("agent_ab".equals(value));
    }

    /** The single model picker serves every template except model_ab (A/B pair). */
    static boolean modelPickerVisible(String template) {
        return !"model_ab".equals(template);
    }

    private void loadSuggestions() {
        try {
            List<Api.Run> runs = client.runs(null, null, null, null, null);
            List<String> models = Links.distinctRuns(runs, Api.Run::model);
            model.setItems(models);
            modelA.setItems(models);
            modelB.setItems(models);
            reviewerModel.setItems(reviewerSuggestions());
            trajectoryReviewerModel.setItems(reviewerSuggestions());
        } catch (Exception ignored) {
            // suggestions are optional; the server still validates
        }
    }

    /** Raw field values by param key, normalized by ExperimentParams.build (unit-tested). */
    private Map<String, Object> rawValues(String currentTemplate) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("no_context_probe", noContextProbe.getValue());
        raw.put("reviewer_model", reviewerModel.getValue());
        raw.put("review_weight", reviewWeight.getValue());
        raw.put("review_blind", reviewBlind.getValue());
        raw.put("trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        raw.put("trajectory_weight", trajectoryWeight.getValue());
        raw.put("trajectory_use", trajectoryUse.getValue());
        switch (currentTemplate) {
            case "harness_effect" -> {
                raw.put("model", model.getValue());
                List<String> arms = new ArrayList<>();
                if (orch.getValue()) arms.add("orch");
                if (mono.getValue()) arms.add("mono");
                if (monoRules.getValue()) arms.add("mono+rules");
                if (par.getValue()) arms.add("par");
                raw.put("arms", arms);
                raw.put("parallel", parallel.getValue());
            }
            case "model_ab" -> {
                raw.put("model_a", modelA.getValue());
                raw.put("model_b", modelB.getValue());
            }
            default -> {
                raw.put("model", model.getValue());
                raw.put("mode", agentMode.getValue());
                raw.put("agents", List.of(agentA.getValue(), agentB.getValue()));
            }
        }
        return raw;
    }

    private void submit() {
        errors.removeAll();
        String currentTemplate = template.getValue();
        Map<String, Object> params;
        try {
            params = ExperimentParams.build(currentTemplate, rawValues(currentTemplate));
        } catch (IllegalArgumentException e) {
            errors.add(Panels.error(e.getMessage()));
            return;
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
}
