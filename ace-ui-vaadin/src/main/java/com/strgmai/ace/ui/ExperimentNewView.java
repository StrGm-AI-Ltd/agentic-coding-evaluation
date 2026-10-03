package com.strgmai.ace.ui;

import com.vaadin.flow.component.HasValidation;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * New experiment form — the Vaadin twin of /experiments/new in the service UI.
 * Templates: harness_effect (orch vs mono arms), model_ab (model A vs B),
 * agent_ab (reference agent vs Pi). Params are normalized by ExperimentParams
 * (unit-tested) and submitted to POST /api/experiments.
 */
@Route(value = "experiments/new", layout = MainLayout.class)
public class ExperimentNewView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(ExperimentNewView.class);

    private final ServiceClient client;

    private final TextField name = new TextField("Experiment name");
    private final Select<String> template = new Select<>();
    private final IntegerField k = new IntegerField("Repeats per arm");
    private final ComboBox<String> task = new ComboBox<>("Task");
    private final IntegerField taskWall = new IntegerField("Task wall-clock budget");
    private final TextField taskTokens = new TextField("Task token budget");
    private final IntegerField firstTokenTimeout = new IntegerField("First-token timeout");
    private final IntegerField compactionTrigger = new IntegerField("Compaction trigger");
    private final IntegerField reviewWallSec = new IntegerField("Review wall-clock budget");
    private final IntegerField contextWindow = new IntegerField("Context window");
    private final Checkbox noContextProbe = new Checkbox("Skip context probe");
    private final ComboBox<String> model = modelPicker("Model");
    private final ComboBox<String> modelA = modelPicker("Model A");
    private final ComboBox<String> modelB = modelPicker("Model B");
    private final ComboBox<String> reviewerModel = modelPicker("Reviewer model");
    private final NumberField reviewWeight = weightField("Review weight");
    private final Checkbox reviewBlind = new Checkbox("Blind review");
    private final ComboBox<String> trajectoryReviewerModel = modelPicker("Trajectory reviewer model");
    private final NumberField trajectoryWeight = weightField("Trajectory weight");
    private final Select<String> trajectoryUse = new Select<>();
    private final NumberField temperature = new NumberField("Temperature");
    private final NumberField topP = new NumberField("Top-p");
    private final IntegerField topK = new IntegerField("Top-k");
    private final NumberField repetitionPenalty = new NumberField("Repetition penalty");
    private final IntegerField maxTokens = new IntegerField("Max tokens");
    private final Select<String> reasoningEffort = new Select<>();
    private final IntegerField parallelPlanWall = new IntegerField("Parallel-plan wall-clock budget");
    private final IntegerField handoffWall = new IntegerField("Handoff wall-clock budget");
    private final IntegerField wrapupWall = new IntegerField("Wrap-up wall-clock budget");
    private final Checkbox orch = new Checkbox("Orchestrated arm");
    private final Checkbox mono = new Checkbox("Monolithic arm");
    private final Checkbox monoRules = new Checkbox("Monolithic + rules arm");
    private final Checkbox par = new Checkbox("Parallel arm");
    private final IntegerField parallel = new IntegerField("Parallel wave size");
    private final Select<String> agentMode = new Select<>();
    private final Select<String> agentA = new Select<>();
    private final Select<String> agentB = new Select<>();
    private final VerticalLayout errors = new VerticalLayout();

    private final VerticalLayout harnessEffectSection = new VerticalLayout();
    private final VerticalLayout modelAbSection = new VerticalLayout();
    private final VerticalLayout agentAbSection = new VerticalLayout();

    private static NumberField weightField(final String label) {
        final var field = new NumberField(label);
        field.setMin(0);
        field.setMax(1);
        return field;
    }

    public ExperimentNewView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        template.setLabel("Template");
        template.setItems("harness_effect", "model_ab", "agent_ab");
        template.setItemLabelGenerator(ExperimentNewView::templateLabel);
        template.setValue("harness_effect");
        template.setTooltipText(Tooltips.TEMPLATE);
        k.setValue(3);
        k.setMin(1);
        k.setMax(20);
        k.setPlaceholder("blank = default (3)");
        k.setTooltipText(Tooltips.K);
        // every experiment used to hardcode ExperimentsService.RUNG with no way to override it;
        // items load async in loadSuggestions(), so - same as JobNewView's task field - no eager
        // setValue here (ComboBox forbids setting a value before it has items); blank falls
        // through to that same RUNG default server-side
        task.setAllowCustomValue(true);
        task.setPlaceholder("L1…L7 rung (blank = L3p_point_in_time)");
        task.setTooltipText(Tooltips.TASK);
        taskWall.setValue(3600);
        taskWall.setMin(1);
        taskWall.setHelperText("seconds, per task");
        taskWall.setTooltipText(Tooltips.EXPERIMENT_TASK_WALL);
        taskTokens.setValue("auto");
        taskTokens.setPlaceholder("auto, or a number");
        taskTokens.setTooltipText(Tooltips.EXPERIMENT_TASK_TOKENS);
        firstTokenTimeout.setValue(180);
        firstTokenTimeout.setMin(1);
        firstTokenTimeout.setHelperText("seconds");
        firstTokenTimeout.setTooltipText(Tooltips.FIRST_TOKEN_TIMEOUT);
        compactionTrigger.setMin(0);
        compactionTrigger.setHelperText("prompt tokens; blank = default (28000), 0 = disabled");
        compactionTrigger.setTooltipText(Tooltips.COMPACTION_TRIGGER);
        reviewWallSec.setMin(1);
        reviewWallSec.setHelperText("seconds, per reviewer session; blank = default (900s)");
        reviewWallSec.setTooltipText(Tooltips.REVIEW_WALL_SEC);
        contextWindow.setMin(1);
        contextWindow.setPlaceholder("blank = probe");
        contextWindow.setTooltipText(Tooltips.CONTEXT_WINDOW);
        noContextProbe.setTooltipText(Tooltips.NO_CONTEXT_PROBE);
        name.setPlaceholder("optional, defaults to template + timestamp");
        name.setTooltipText(Tooltips.EXPERIMENT_NAME);
        model.setTooltipText(Tooltips.MODEL);
        parallel.setValue(3);
        parallel.setMin(2);
        parallel.setMax(20);
        parallel.setEnabled(false); // par is unchecked by default; the value-change listener syncs it afterwards
        parallel.setTooltipText(Tooltips.EXPERIMENT_PARALLEL);
        orch.setTooltipText(Tooltips.ARM_ORCH);
        mono.setTooltipText(Tooltips.ARM_MONO);
        monoRules.setTooltipText(Tooltips.ARM_MONO_RULES);
        par.setTooltipText(Tooltips.ARM_PAR);
        modelA.setTooltipText(Tooltips.MODEL_A);
        modelB.setTooltipText(Tooltips.MODEL_B);
        agentMode.setLabel("Mode");
        agentMode.setItems("orchestrated", "monolithic");
        agentMode.setValue("orchestrated");
        agentMode.setTooltipText(Tooltips.AGENT_MODE);
        agentA.setLabel("Agent A");
        agentB.setLabel("Agent B");
        for (final var agent : List.of(agentA, agentB)) {
            agent.setItems("ref", "pi");
        }
        agentA.setValue("ref");
        agentB.setValue("pi");
        agentA.setTooltipText(Tooltips.AGENT_A);
        agentB.setTooltipText(Tooltips.AGENT_B);
        reviewerModel.setTooltipText(Tooltips.REVIEWER_MODEL);
        reviewWeight.setPlaceholder("blank = default (0.1)");
        reviewWeight.setTooltipText(Tooltips.REVIEW_WEIGHT);
        reviewBlind.setTooltipText(Tooltips.REVIEW_BLIND);
        trajectoryReviewerModel.setTooltipText(Tooltips.TRAJECTORY_REVIEWER_MODEL);
        trajectoryWeight.setPlaceholder("blank = default (0.1)");
        trajectoryWeight.setTooltipText(Tooltips.TRAJECTORY_WEIGHT);
        trajectoryUse.setLabel("Trajectory use");
        trajectoryUse.setItems("", "calibration", "direct");
        trajectoryUse.setValue("");
        trajectoryUse.setItemLabelGenerator(value -> value.isEmpty() ? "—" : value);
        trajectoryUse.setTooltipText(Tooltips.TRAJECTORY_USE);
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
        reasoningEffort.setLabel("Reasoning effort");
        reasoningEffort.setItems("", "none", "low", "medium", "high");
        reasoningEffort.setValue("");
        reasoningEffort.setItemLabelGenerator(v -> v.isEmpty() ? "blank = default per phase" : v.equals("none") ? "none (disable thinking)" : v);
        reasoningEffort.setTooltipText(Tooltips.REASONING_EFFORT);
        parallelPlanWall.setMin(1);
        parallelPlanWall.setPlaceholder("blank = default (600s)");
        parallelPlanWall.setTooltipText(Tooltips.PARALLEL_PLAN_WALL);
        handoffWall.setMin(1);
        handoffWall.setPlaceholder("blank = default (300s)");
        handoffWall.setTooltipText(Tooltips.HANDOFF_WALL);
        wrapupWall.setMin(1);
        wrapupWall.setHelperText("blank = default (300s, before the decode-speed scale)");
        wrapupWall.setTooltipText(Tooltips.WRAPUP_WALL);

        orch.setValue(true);
        mono.setValue(true);

        add(new H2("New experiment"));
        add(new RouterLink("← Experiments", ExperimentsView.class));
        errors.setPadding(false);
        add(errors);

        add(Forms.section("Experiment",
                Forms.row(name, template, k, model),
                Forms.row(task),
                Forms.row(taskWall, taskTokens, contextWindow, noContextProbe),
                Forms.row(firstTokenTimeout, compactionTrigger, reviewWallSec),
                Forms.row(parallelPlanWall, handoffWall, wrapupWall)));

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

        add(Forms.section("Sampler (all templates, all arms)",
                Forms.row(temperature, topP, topK, repetitionPenalty),
                Forms.row(maxTokens, reasoningEffort)));

        updateVisibility();
        template.addValueChangeListener(e -> updateVisibility());
        par.addValueChangeListener(e -> parallel.setEnabled(par.getValue()));

        final var submit = new Button("Enqueue experiment", e -> submit());
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

    private static String templateLabel(final String template) {
        return switch (template) {
            case "model_ab" -> "model_ab — model A vs model B, same harness/budgets";
            case "agent_ab" -> "agent_ab — reference agent vs Pi, same model/budgets";
            default -> "harness_effect — orchestrated vs monolithic (vs parallel, vs prompt-only rules)";
        };
    }

    private static ComboBox<String> modelPicker(final String label) {
        // returned as ComboBox<String>; the constructor arg doesn't fix the diamond's type
        // parameter, so empty-diamond under var would infer ComboBox<Object> and fail on return
        final ComboBox<String> picker = new ComboBox<>(label);
        picker.setAllowCustomValue(true);
        if (label.startsWith("Reviewer") || label.startsWith("Trajectory")) {
            picker.setPlaceholder("provider/model — " + reviewerSuggestions().get(0) + "…");
        } else {
            picker.setPlaceholder("local model id, or pick a seen model");
        }
        return picker;
    }

    private void updateVisibility() {
        final var value = template.getValue();
        model.setVisible(modelPickerVisible(value)); // shared by harness_effect and agent_ab
        harnessEffectSection.setVisible("harness_effect".equals(value));
        modelAbSection.setVisible("model_ab".equals(value));
        agentAbSection.setVisible("agent_ab".equals(value));
    }

    /** The single model picker serves every template except model_ab (A/B pair). */
    static boolean modelPickerVisible(final String template) {
        return !"model_ab".equals(template);
    }

    private void loadSuggestions() {
        try {
            task.setItems(client.tasks());
        } catch (final Exception e) {
            log.warn("could not load the rung list: {}", e.toString());
        }
        try {
            final var runs = client.runs(null, null, null, null, null);
            final var models = Links.distinctRuns(runs, Api.Run::model);
            model.setItems(models);
            modelA.setItems(models);
            modelB.setItems(models);
            reviewerModel.setItems(reviewerSuggestions());
            trajectoryReviewerModel.setItems(reviewerSuggestions());
        } catch (final Exception e) {
            log.warn("could not load past-run model/reviewer suggestions: {}", e.toString());
        }
        try {
            // the model server's own list, merged in on top of past-run models (GET /api/models) -
            // a cold backend with zero run history still gets a useful picker, not an empty one
            final var live = client.models();
            final var merged = Stream.concat(model.getGenericDataView().getItems(), live.stream())
                    .distinct().sorted().toList();
            model.setItems(merged);
            modelA.setItems(merged);
            modelB.setItems(merged);
        } catch (final Exception e) {
            // the model server may be unreachable; past-run suggestions (if any) still stand
            log.warn("could not reach the model server for live model suggestions: {}", e.toString());
        }
    }

    /** Raw field values by param key, normalized by ExperimentParams.build (unit-tested). */
    private Map<String, Object> rawValues(final String currentTemplate) {
        // returned as Map<String, Object>; empty-diamond under var would infer <Object, Object>
        final Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("task", task.getValue());
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("first_token_timeout", firstTokenTimeout.getValue());
        raw.put("compaction_trigger", compactionTrigger.getValue());
        raw.put("review_wall_sec", reviewWallSec.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("no_context_probe", noContextProbe.getValue());
        raw.put("reviewer_model", reviewerModel.getValue());
        raw.put("review_weight", reviewWeight.getValue());
        raw.put("review_blind", reviewBlind.getValue());
        raw.put("trajectory_reviewer_model", trajectoryReviewerModel.getValue());
        raw.put("trajectory_weight", trajectoryWeight.getValue());
        raw.put("trajectory_use", trajectoryUse.getValue());
        raw.put("temperature", temperature.getValue());
        raw.put("top_p", topP.getValue());
        raw.put("top_k", topK.getValue());
        raw.put("repetition_penalty", repetitionPenalty.getValue());
        raw.put("max_tokens", maxTokens.getValue());
        raw.put("reasoning_effort", reasoningEffort.getValue());
        raw.put("parallel_plan_wall", parallelPlanWall.getValue());
        raw.put("handoff_wall", handoffWall.getValue());
        raw.put("wrapup_wall", wrapupWall.getValue());
        switch (currentTemplate) {
            case "harness_effect" -> {
                raw.put("model", model.getValue());
                final var arms = new ArrayList<String>();
                if (orch.getValue()) arms.add("orch");
                if (mono.getValue()) arms.add("mono");
                if (Boolean.TRUE.equals(monoRules.getValue())) arms.add("mono+rules");
                if (Boolean.TRUE.equals(par.getValue())) arms.add("par");
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

    /** Every field ExperimentParams.build can name in a "key: message" validation error, keyed
     *  exactly like rawValues() - so a thrown error can be routed back to the field it's actually
     *  about instead of only a generic panel. Not every raw key's field can fail this way (String
     *  fields have no numeric/boolean parse to fail), but listing them anyway costs nothing and
     *  keeps this in sync with rawValues() by construction. */
    private Map<String, HasValidation> fieldsByKey() {
        return Map.ofEntries(
                Map.entry("task_wall", taskWall), Map.entry("task_tokens", taskTokens),
                Map.entry("first_token_timeout", firstTokenTimeout), Map.entry("compaction_trigger", compactionTrigger),
                Map.entry("review_wall_sec", reviewWallSec), Map.entry("context_window", contextWindow),
                Map.entry("reviewer_model", reviewerModel), Map.entry("review_weight", reviewWeight),
                Map.entry("trajectory_reviewer_model", trajectoryReviewerModel), Map.entry("trajectory_weight", trajectoryWeight),
                Map.entry("temperature", temperature), Map.entry("top_p", topP), Map.entry("top_k", topK),
                Map.entry("repetition_penalty", repetitionPenalty), Map.entry("max_tokens", maxTokens),
                Map.entry("parallel_plan_wall", parallelPlanWall), Map.entry("handoff_wall", handoffWall),
                Map.entry("wrapup_wall", wrapupWall), Map.entry("model", model), Map.entry("model_a", modelA),
                Map.entry("model_b", modelB), Map.entry("parallel", parallel));
    }

    /** ExperimentParams.build names the offending field as a "key: message" prefix for most of
     *  its checks (see intOr/putIfPresent/taskTokens) - split that out so the error can be routed
     *  to the field it's actually about, a red field + inline message, instead of only the generic
     *  panel below. Pure and package-private so it's unit-testable without constructing the view.
     *  The handful of cross-field messages ExperimentParams.build throws with no such prefix (e.g.
     *  "a model is required for every template", which could mean one of several possible fields
     *  depending on the template) return null and fall through to the generic panel, which is the
     *  right place for a message that isn't about one single field. */
    static String[] splitFieldError(final String message, final Set<String> knownKeys) {
        final int colon = message.indexOf(": ");
        if (colon < 0) return null;
        final var key = message.substring(0, colon);
        return knownKeys.contains(key) ? new String[]{key, message.substring(colon + 2)} : null;
    }

    private boolean showFieldError(final String message) {
        final var fields = fieldsByKey();
        final var split = splitFieldError(message, fields.keySet());
        if (split == null) return false;
        fields.get(split[0]).setInvalid(true);
        fields.get(split[0]).setErrorMessage(split[1]);
        return true;
    }

    private void submit() {
        errors.removeAll();
        fieldsByKey().values().forEach(f -> f.setInvalid(false));
        final var currentTemplate = template.getValue();
        final Map<String, Object> params;   // assigned exactly once below; a legal blank final
        try {
            params = ExperimentParams.build(currentTemplate, rawValues(currentTemplate));
        } catch (final IllegalArgumentException e) {
            if (!showFieldError(e.getMessage())) {
                errors.add(Panels.error(e.getMessage()));
            }
            return;
        }

        final var experimentName = name.getValue() == null || name.getValue().isBlank()
                ? currentTemplate + " " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                : name.getValue();

        try {
            final var experiment = client.createExperiment(experimentName, currentTemplate, params,
                    k.getValue() == null ? 3 : k.getValue());
            Notification.show("Queued experiment #" + experiment.id() + " with "
                    + (experiment.jobs() == null ? 0 : experiment.jobs().size()) + " jobs",
                    4000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.navigate("experiments/" + experiment.id()));
        } catch (final Exception e) {
            log.warn("could not create experiment '{}': {}", experimentName, e.toString());
            errors.add(Panels.error(client.errorText(e)));
        }
    }
}
