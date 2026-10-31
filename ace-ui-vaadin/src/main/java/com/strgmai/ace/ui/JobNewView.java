package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New job form — the Vaadin twin of /jobs/new in the service UI: every RunSpec flag
 * (queue.py) that run_bench.py accepts, plus queue priority. Field values are
 * normalized by JobSpecs (unit-tested) and submitted to POST /api/jobs.
 *
 * <p>#569 "Anchored Scan": one continuous page, same nine sections as before, but every section
 * is now an always-visible bordered tile (the Job Detail page's own chrome - border/radius/
 * background from the same Aura tokens JobLiveSection's liveTile() and JobDetailPrototypeView use,
 * see anchoredTile() below) instead of the Details-based accordion collapsibleSection() used to
 * build, and a sticky left rail (buildAnchorNav()) jumps straight to any of them - picked over four
 * other researched directions (a wizard, an essentials+grid layout, a live preview panel, a command-
 * palette search) as the closest literal match to the issue's own "look similar to the job detail
 * window" ask, and the one requiring the smallest structural departure from this form's existing
 * field wiring. SECTIONS (just below) is the single source of truth for the rail's own entries and
 * the client-side scrollspy's own observed-id list - the tiles themselves are still built inline in
 * the constructor (their field composition differs per section, so a fully data-driven loop would
 * need its own per-section row-building indirection for no real gain here). */
@Route(value = "jobs/new", layout = MainLayout.class)
public class JobNewView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(JobNewView.class);

    /** id (the tile's own DOM id, and "navlink-" + id for its rail entry) paired with its title -
     *  in the SAME order the tiles themselves are added, so the rail always lists them top to
     *  bottom in on-page order. */
    private static final String[][] SECTIONS = {
            {"anchor-run", "Run"},
            {"anchor-budgets", "Budgets"},
            {"anchor-compaction", "Context compaction"},
            {"anchor-lessons", "Cross-task lessons"},
            {"anchor-sampler", "Sampler"},
            {"anchor-reviewers", "Reviewers"},
            {"anchor-judge", "Judge"},
            {"anchor-rescue", "Budget rescue"},
            {"anchor-flags", "Flags"},
    };

    private final ServiceClient client;

    private final ComboBox<String> task = new ComboBox<>("Task");
    private final ProviderModelPicker model;
    private final ProviderModelPicker reviewerModel;
    private final ProviderModelPicker trajectoryReviewerModel;
    private final Select<String> harness = new Select<>();
    private final Select<String> mode = new Select<>();
    private final Select<String> planSource = new Select<>();
    private final Select<String> taskGranularity = new Select<>();
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
    private final CompactionStrategyList compactionStrategies = new CompactionStrategyList();
    // #332: a dedicated toggle, not a CompactionStrategies member - see Tooltips.LESSON_RECORDING_ENABLED
    private final Checkbox lessonRecordingEnabled = new Checkbox("Cross-task lesson recording");
    private final IntegerField reviewWallSec = new IntegerField("Review wall-clock budget");
    private final IntegerField reviewTokens = new IntegerField("Review token budget");
    private final IntegerField maxTurns = new IntegerField("Max turns");
    private final IntegerField fixWall = new IntegerField("Fix-up wall-clock budget");
    private final IntegerField fixTokens = new IntegerField("Fix-up token budget");
    private final IntegerField parallelPlanTokens = new IntegerField("Parallel-plan token budget");
    private final IntegerField handoffTokens = new IntegerField("Handoff token budget");
    private final IntegerField wrapupTokens = new IntegerField("Wrap-up token budget");
    private final IntegerField wallBudget = new IntegerField("Wall budget override (all phases)");
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
    // #269: run-level judge baseline - see Tooltips.JUDGE_ENABLED for why this must exist here,
    // not only on the per-task Restart tab (JobDetailView.buildRestartTab()).
    private final Checkbox judgeEnabled = new Checkbox("Mid-task judge");
    private final IntegerField judgeEveryNTurns = new IntegerField("Judge every N turns");
    private final ProviderModelPicker judgeModel;
    private final IntegerField judgeWallSec = new IntegerField("Judge wall-clock budget");
    private final IntegerField judgeTokens = new IntegerField("Judge token budget");
    // #307: the budget-exhaustion rescue step - same run-level-baseline reasoning as the judge above
    private final Checkbox budgetRescueEnabled = new Checkbox("Budget-exhaustion rescue");
    private final IntegerField rescueWallSec = new IntegerField("Rescue wall-clock budget");
    private final IntegerField rescueTokens = new IntegerField("Rescue token budget");
    private final IntegerField rescueKeepRecentTurns = new IntegerField("Rescue: recent turns kept");
    private final VerticalLayout errors = new VerticalLayout();
    // populated once in loadSuggestions(); looked up by name as the operator picks a rung, so
    // picking a task shows what it actually tests before the job is ever enqueued
    private List<Api.RungDetail> rungDetails = List.of();
    private final com.vaadin.flow.component.html.Div taskInfo = Panels.mono("");

    public JobNewView(final ServiceClient client) {
        this.client = client;
        model = new ProviderModelPicker(client, "Model").withTooltipText(Tooltips.MODEL);
        reviewerModel = new ProviderModelPicker(client, "Reviewer model").withTooltipText(Tooltips.REVIEWER_MODEL);
        trajectoryReviewerModel = new ProviderModelPicker(client, "Trajectory reviewer model")
                .withTooltipText(Tooltips.TRAJECTORY_REVIEWER_MODEL);
        judgeModel = new ProviderModelPicker(client, "Judge model").withTooltipText(Tooltips.JUDGE_MODEL);
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
        runIdField.setTooltipText(Tooltips.RUN_ID);

        configureSelect(harness, "", "ref", "pi");
        configureSelect(mode, "", "monolithic", "orchestrated");
        configureSelect(planSource, "", "agent", "reference");
        configureSelect(taskGranularity, "", "fine", "medium", "coarse");
        configureSelect(parallelPlan, "", "on", "off");
        configureSelect(trajectoryUse, "", "calibration", "direct");
        configureSelect(reasoningEffort, "", "none", "low", "medium", "high");
        harness.setLabel("Harness");
        mode.setLabel("Mode");
        planSource.setLabel("Plan source");
        taskGranularity.setLabel("Task granularity");
        parallelPlan.setLabel("Parallel planning");
        trajectoryUse.setLabel("Trajectory use");
        reasoningEffort.setLabel("Reasoning effort");
        reasoningEffort.setItemLabelGenerator(v -> v.isEmpty() ? "blank = default per phase" : v.equals("none") ? "none (disable thinking)" : v);
        harness.setTooltipText(Tooltips.HARNESS);
        mode.setTooltipText(Tooltips.MODE);
        planSource.setTooltipText(Tooltips.PLAN_SOURCE);
        taskGranularity.setTooltipText(Tooltips.TASK_GRANULARITY);
        parallelPlan.setTooltipText(Tooltips.PARALLEL_PLAN);
        trajectoryUse.setTooltipText(Tooltips.TRAJECTORY_USE);
        reasoningEffort.setTooltipText(Tooltips.REASONING_EFFORT);

        // the ids RunSpec.PHASES/RunBench.PHASES accept - L7_full_platform's and L3p_point_in_time's
        // own multi-phase sequence; blank/none selected = every phase the rung defines (the default)
        phases.setItems("p0_definition", "p1_plan", "p2_implementation");
        phases.setTooltipText(Tooltips.PHASES);
        parallel.setPlaceholder("auto, or 1–99");
        parallel.setTooltipText(Tooltips.PARALLEL);
        priority.setValue(0);
        priority.setTooltipText(Tooltips.PRIORITY);
        taskWall.setMin(1);
        taskWall.setTooltipText(Tooltips.TASK_WALL);
        taskTokens.setMin(1);
        taskTokens.setTooltipText(Tooltips.TASK_TOKENS);
        implWall.setMin(1);
        implWall.setTooltipText(Tooltips.IMPL_WALL);
        implTokens.setMin(1);
        implTokens.setTooltipText(Tooltips.IMPL_TOKENS);
        planWall.setMin(0);
        planWall.setTooltipText(Tooltips.PLAN_WALL);
        planTokens.setMin(1);
        planTokens.setTooltipText(Tooltips.PLAN_TOKENS);
        contextWindow.setMin(1);
        contextWindow.setTooltipText(Tooltips.CONTEXT_WINDOW);
        firstTokenTimeout.setMin(1);
        firstTokenTimeout.setTooltipText(Tooltips.FIRST_TOKEN_TIMEOUT);
        compactionTrigger.setMin(0);
        compactionTrigger.setTooltipText(Tooltips.COMPACTION_TRIGGER);
        compactionStrategies.setValue(List.of(CompactionStrategyList.TOOL_RESULT_MASK, CompactionStrategyList.ERROR_LINE_EXTRACTION,
                CompactionStrategyList.TOOL_OUTPUT_RECALL));   // mirrors CompactionStrategies.DEFAULT on the service side
        lessonRecordingEnabled.setValue(true);   // on by default now - mirrors RunSpec.from()'s own default
        lessonRecordingEnabled.setTooltipText(Tooltips.LESSON_RECORDING_ENABLED);
        reviewWallSec.setMin(1);
        reviewWallSec.setTooltipText(Tooltips.REVIEW_WALL_SEC);
        reviewTokens.setMin(0);
        reviewTokens.setTooltipText(Tooltips.REVIEW_TOKENS);
        maxTurns.setMin(1);
        maxTurns.setTooltipText(Tooltips.MAX_TURNS);
        fixWall.setMin(0);
        fixWall.setTooltipText(Tooltips.FIX_WALL);
        fixTokens.setMin(0);
        fixTokens.setTooltipText(Tooltips.FIX_TOKENS);
        parallelPlanTokens.setMin(0);
        parallelPlanTokens.setTooltipText(Tooltips.PARALLEL_PLAN_TOKENS);
        handoffTokens.setMin(0);
        handoffTokens.setTooltipText(Tooltips.HANDOFF_TOKENS);
        wrapupTokens.setMin(0);
        wrapupTokens.setTooltipText(Tooltips.WRAPUP_TOKENS);
        wallBudget.setMin(1);
        wallBudget.setTooltipText(Tooltips.WALL_BUDGET);
        parallelWeight.setMin(0);
        parallelWeight.setMax(1);
        parallelWeight.setTooltipText(Tooltips.PARALLEL_WEIGHT);
        efficiencyWeight.setMin(0);
        efficiencyWeight.setMax(1);
        efficiencyWeight.setTooltipText(Tooltips.EFFICIENCY_WEIGHT);
        reviewWeight.setMin(0);
        reviewWeight.setMax(1);
        reviewWeight.setTooltipText(Tooltips.REVIEW_WEIGHT);
        trajectoryWeight.setMin(0);
        trajectoryWeight.setMax(1);
        trajectoryWeight.setTooltipText(Tooltips.TRAJECTORY_WEIGHT);
        temperature.setMin(0);
        temperature.setTooltipText(Tooltips.TEMPERATURE);
        topP.setMin(0);
        topP.setMax(1);
        topP.setTooltipText(Tooltips.TOP_P);
        topK.setMin(1);
        topK.setTooltipText(Tooltips.TOP_K);
        repetitionPenalty.setMin(0);
        repetitionPenalty.setTooltipText(Tooltips.REPETITION_PENALTY);
        maxTokens.setMin(1);
        maxTokens.setTooltipText(Tooltips.MAX_TOKENS);
        parallelPlanWall.setMin(1);
        parallelPlanWall.setTooltipText(Tooltips.PARALLEL_PLAN_WALL);
        handoffWall.setMin(1);
        handoffWall.setTooltipText(Tooltips.HANDOFF_WALL);
        wrapupWall.setMin(1);
        wrapupWall.setTooltipText(Tooltips.WRAPUP_WALL);
        javaHome.setTooltipText(Tooltips.JAVA_HOME);
        handoffNotes.setTooltipText(Tooltips.HANDOFF_NOTES);
        systemRules.setTooltipText(Tooltips.SYSTEM_RULES);
        selfReview.setTooltipText(Tooltips.SELF_REVIEW);
        reviewBlind.setTooltipText(Tooltips.REVIEW_BLIND);
        trajectoryReview.setTooltipText(Tooltips.TRAJECTORY_REVIEW);
        noContextProbe.setTooltipText(Tooltips.NO_CONTEXT_PROBE);
        contextProbeFresh.setTooltipText(Tooltips.CONTEXT_PROBE_FRESH);
        judgeEnabled.setValue(true);   // on by default now - mirrors RunSpec.from()'s own default
        judgeEnabled.setTooltipText(Tooltips.JUDGE_ENABLED);
        judgeEveryNTurns.setMin(1);
        judgeEveryNTurns.setTooltipText(Tooltips.JUDGE_EVERY_N_TURNS);
        judgeWallSec.setMin(1);
        judgeWallSec.setTooltipText(Tooltips.JUDGE_WALL_SEC);
        judgeTokens.setMin(1);
        judgeTokens.setTooltipText(Tooltips.JUDGE_TOKENS);
        budgetRescueEnabled.setTooltipText(Tooltips.BUDGET_RESCUE_ENABLED);
        rescueWallSec.setMin(1);
        rescueWallSec.setTooltipText(Tooltips.RESCUE_WALL_SEC);
        rescueTokens.setMin(1);
        rescueTokens.setTooltipText(Tooltips.RESCUE_TOKENS);
        rescueKeepRecentTurns.setMin(0);
        rescueKeepRecentTurns.setTooltipText(Tooltips.RESCUE_KEEP_RECENT_TURNS);

        add(new ViewHeader("New job"));
        add(new RouterLink("← Queue", JobsView.class));
        add(TabManual.banner(this, "Configure every flag for a new benchmark run.", "User-Manual-Jobs"));
        errors.setPadding(false);
        add(errors);

        // the two fields nearly every job actually sets - kept unboxed, right under the page
        // title, ahead of every other (collapsed-by-default) section
        final var taskModelRow = new HorizontalLayout(task, model);
        taskModelRow.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        taskModelRow.getStyle().set("flex-wrap", "wrap");
        taskModelRow.setSpacing(true);
        add(taskModelRow);
        add(taskInfo);

        final var submit = new Button("Enqueue job", e -> submit());
        submit.getStyle().set("margin-top", "12px");

        final var sectionsColumn = new VerticalLayout(
                anchoredTile(SECTIONS[0][0], SECTIONS[0][1],
                        Forms.row(runIdField, harness, mode, planSource, taskGranularity),
                        Forms.row(phases, parallel, parallelPlan, parallelWeight)),
                anchoredTile(SECTIONS[1][0], SECTIONS[1][1],
                        Forms.row(taskWall, taskTokens, implWall, implTokens),
                        Forms.row(planWall, planTokens, wallBudget, contextWindow),
                        Forms.row(firstTokenTimeout, maxTurns),
                        Forms.row(parallelPlanWall, handoffWall, wrapupWall),
                        Forms.row(parallelPlanTokens, handoffTokens, wrapupTokens),
                        Forms.row(fixWall, fixTokens),
                        Forms.row(efficiencyWeight)),
                anchoredTile(SECTIONS[2][0], SECTIONS[2][1],
                        Forms.row(compactionTrigger),
                        Forms.row(compactionStrategies)),
                anchoredTile(SECTIONS[3][0], SECTIONS[3][1],
                        Forms.row(lessonRecordingEnabled)),
                anchoredTile(SECTIONS[4][0], SECTIONS[4][1],
                        Forms.row(temperature, topP, topK, repetitionPenalty),
                        Forms.row(maxTokens, reasoningEffort)),
                anchoredTile(SECTIONS[5][0], SECTIONS[5][1],
                        Forms.row(reviewerModel, reviewWeight),
                        Forms.row(trajectoryReviewerModel, trajectoryWeight, trajectoryUse),
                        Forms.row(reviewBlind, trajectoryReview),
                        Forms.row(reviewWallSec, reviewTokens)),
                anchoredTile(SECTIONS[6][0], SECTIONS[6][1],
                        Forms.row(judgeEnabled, judgeEveryNTurns, judgeModel),
                        Forms.row(judgeWallSec, judgeTokens)),
                anchoredTile(SECTIONS[7][0], SECTIONS[7][1],
                        Forms.row(budgetRescueEnabled, rescueWallSec, rescueTokens, rescueKeepRecentTurns)),
                anchoredTile(SECTIONS[8][0], SECTIONS[8][1],
                        Forms.row(handoffNotes, systemRules, selfReview),
                        Forms.row(javaHome),
                        Forms.row(noContextProbe, contextProbeFresh),
                        Forms.row(priority)),
                submit);
        sectionsColumn.setPadding(false);
        sectionsColumn.getStyle().set("gap", "16px").set("flex", "1 1 auto").set("min-width", "0");

        final var scanLayout = new HorizontalLayout(buildAnchorNav(), sectionsColumn);
        scanLayout.setWidthFull();
        scanLayout.setPadding(false);
        scanLayout.setSpacing(false);
        scanLayout.getStyle().set("align-items", "flex-start").set("gap", "24px");
        add(scanLayout);

        addAttachListener(e -> {
            loadSuggestions();
            armScrollspy();
        });
    }

    /** #569: the same bordered-tile chrome the Job Detail page's own live/dashboard tiles use
     *  (JobLiveSection.liveTile(), JobDetailPrototypeView) - border/radius/background drawn from
     *  the real Aura tokens confirmed in ace-ui-vaadin/node_modules/@vaadin/aura/src/color.css,
     *  not invented. Every section stays open (unlike the old Details-based collapsibleSection()):
     *  the sticky rail is what keeps fifty fields reachable now, not collapsing most of them away
     *  by default. id becomes the tile's own DOM id, the rail's and the scrollspy's own jump/observe
     *  target. */
    private static VerticalLayout anchoredTile(final String id, final String title, final Component[]... rows) {
        final var section = Forms.section(title, rows);
        section.getElement().setAttribute("id", id);
        section.getStyle()
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "var(--vaadin-radius-l)")
                .set("background", "var(--vaadin-background-container)")
                .set("padding", "14px 20px")
                .set("margin", "0")
                .set("box-sizing", "border-box");
        return section;
    }

    /** #569: one clickable entry per SECTIONS, each scrolling its own tile into view on click.
     *  Found live: a plain Anchor("#anchor-judge", ...) here gets intercepted by Vaadin's own
     *  client-side router (every in-app <a> click is examined for app navigation, including a bare
     *  #fragment href) and silently mis-routed to an unrelated view instead of jumping down the
     *  page - confirmed live, repeatedly, clicking "Judge" landed on /info. A plain Span with a
     *  server-side click listener calling scrollIntoView() via executeJs() sidesteps the router
     *  entirely - the small round-trip cost is irrelevant for a once-in-a-while navigation click. */
    private static Div buildAnchorNav() {
        final var nav = new Div();
        nav.getStyle()
                .set("position", "sticky").set("top", "0")
                .set("display", "flex").set("flex-direction", "column").set("gap", "2px")
                .set("border-right", "1px solid var(--vaadin-border-color-secondary)")
                .set("padding", "2px 16px 2px 0")
                .set("flex", "0 0 180px")
                .set("align-self", "flex-start");
        for (final var section : SECTIONS) {
            final var link = new com.vaadin.flow.component.html.Span(section[1]);
            link.getElement().setAttribute("id", "navlink-" + section[0]);
            link.getStyle()
                    .set("font-size", "0.85em")
                    .set("color", "var(--vaadin-text-color-secondary)")
                    .set("cursor", "pointer")
                    .set("padding", "6px 10px")
                    .set("border-radius", "var(--vaadin-radius-s)")
                    .set("border-left", "2px solid transparent");
            link.addClickListener(e -> link.getElement().executeJs(
                    "document.getElementById($0)?.scrollIntoView({behavior: 'smooth', block: 'start'})", section[0]));
            nav.add(link);
        }
        return nav;
    }

    /** #569: highlights whichever SECTIONS tile is currently in view - the same IntersectionObserver
     *  pattern the design mockup's own JS used for this exact direction, reproduced here against
     *  the real rendered DOM ids rather than static markup. Run once per attach (armScrollspy()
     *  observes real elements already in the DOM at that point, unlike the constructor itself,
     *  where nothing is attached yet).
     *
     *  <p>Found live while testing the click-to-jump fix above: near the END of the page, the
     *  trailing sections (Budget rescue, Flags) are short enough that several of them - plus
     *  whichever section was just scrolled to - can ALL cross the 0.4 threshold in the same
     *  viewport at once (the page has no more room to scroll further, so it can't isolate just the
     *  target section the way it does higher up the page). A naive "last entry in this callback's
     *  own batch wins" picks whichever one the browser happens to report last, NOT the one the user
     *  actually jumped to - confirmed live, clicking "Judge" lit up "Flags" instead. Fix: track
     *  visibility per id across calls (not just the current batch) and, each time it changes,
     *  highlight whichever currently-visible id comes FIRST in SECTIONS order - the topmost visible
     *  section, matching what a reader actually sees at the top of their screen and what a jump
     *  target lands on. */
    private void armScrollspy() {
        final var ids = new java.util.ArrayList<String>();
        for (final var section : SECTIONS) ids.add(section[0]);
        getElement().executeJs(
                "const order = $0;"
                        + "const sections = order.map(id => document.getElementById(id)).filter(Boolean);"
                        + "const links = () => document.querySelectorAll('[id^=\"navlink-\"]');"
                        + "const visible = new Set();"
                        + "const obs = new IntersectionObserver((entries) => {"
                        + "  entries.forEach((en) => { en.isIntersecting ? visible.add(en.target.id) : visible.delete(en.target.id); });"
                        + "  const activeId = order.find(id => visible.has(id));"
                        + "  links().forEach(a => { a.style.color = 'var(--vaadin-text-color-secondary)'; a.style.borderLeftColor = 'transparent'; });"
                        + "  const active = activeId && document.getElementById('navlink-' + activeId);"
                        + "  if (active) { active.style.color = 'var(--vaadin-text-color)'; active.style.borderLeftColor = 'var(--vaadin-focus-ring-color)'; }"
                        + "}, {threshold: 0.4});"
                        + "sections.forEach(s => obs.observe(s));",
                ids);
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
        raw.put("task_granularity", taskGranularity.getValue());
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
        raw.put("task_wall", taskWall.getValue());
        raw.put("task_tokens", taskTokens.getValue());
        raw.put("impl_wall", implWall.getValue());
        raw.put("impl_tokens", implTokens.getValue());
        raw.put("plan_wall", planWall.getValue());
        raw.put("plan_tokens", planTokens.getValue());
        raw.put("context_window", contextWindow.getValue());
        raw.put("first_token_timeout", firstTokenTimeout.getValue());
        raw.put("compaction_trigger", compactionTrigger.getValue());
        raw.put("compaction_strategies", String.join(",", compactionStrategies.getValue()));
        raw.put("lesson_recording_enabled", lessonRecordingEnabled.getValue());
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
        raw.put("judge_enabled", judgeEnabled.getValue());
        raw.put("judge_every_n_turns", judgeEveryNTurns.getValue());
        raw.put("judge_model", judgeModel.getValue());
        raw.put("judge_wall_sec", judgeWallSec.getValue());
        raw.put("judge_tokens", judgeTokens.getValue());
        raw.put("budget_rescue_enabled", budgetRescueEnabled.getValue());
        raw.put("rescue_wall_sec", rescueWallSec.getValue());
        raw.put("rescue_tokens", rescueTokens.getValue());
        raw.put("rescue_keep_recent_turns", rescueKeepRecentTurns.getValue());
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
