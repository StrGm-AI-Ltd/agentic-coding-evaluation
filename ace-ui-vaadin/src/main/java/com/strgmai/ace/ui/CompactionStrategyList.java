package com.strgmai.ace.ui;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #417: toggle-on/off + reorder control for ACE's pluggable compaction strategies
 * (CompactionStrategies.KNOWN on the service side - this module has no dependency on ace-service's
 * domain classes, so the known names/labels are mirrored here as literals, the same way
 * JobNewView's own phases.setItems(...) already mirrors RunSpec.PHASES). Plain up/down buttons per
 * row, not drag-and-drop (no DnD precedent anywhere in this module - see #417's own plan).
 * getValue()/setValue() round-trip the enabled rows' names, in order, as a comma-separated
 * String - the same shape "phases" already uses for its own ordered-name-list config.
 */
public class CompactionStrategyList extends VerticalLayout {

    /** mirrors CompactionStrategies' own constants on the service side - keep in sync by hand with
     *  the service-side names. */
    public static final String TOOL_RESULT_MASK = "tool_result_mask";
    public static final String STRUCTURED_EVICTION = "structured_eviction";
    public static final String ERROR_LINE_EXTRACTION = "error_line_extraction";
    public static final String TOOL_OUTPUT_RECALL = "tool_output_recall";
    /** #332: not itself a compaction mechanism - offers the record_lesson tool, reusing this
     *  exact pluggable-session-capability control rather than a second, parallel toggle UI. */
    public static final String LESSON_RECORDING = "lesson_recording";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(TOOL_RESULT_MASK, "Tool-result masking");
        LABELS.put(ERROR_LINE_EXTRACTION, "Error-line extraction");
        LABELS.put(TOOL_OUTPUT_RECALL, "Tool-output recall");
        LABELS.put(LESSON_RECORDING, "Cross-task lesson recording");
        LABELS.put(STRUCTURED_EVICTION, "Structured eviction");
    }

    /** one tooltip per mechanism - each describes that specific algorithm, not the control as a
     *  whole, so a row can be understood on its own without reading every other row first. */
    private static final Map<String, String> TOOLTIPS = new LinkedHashMap<>();
    static {
        TOOLTIPS.put(TOOL_RESULT_MASK, Tooltips.COMPACTION_TOOL_RESULT_MASK);
        TOOLTIPS.put(ERROR_LINE_EXTRACTION, Tooltips.COMPACTION_ERROR_LINE_EXTRACTION);
        TOOLTIPS.put(TOOL_OUTPUT_RECALL, Tooltips.COMPACTION_TOOL_OUTPUT_RECALL);
        TOOLTIPS.put(LESSON_RECORDING, Tooltips.COMPACTION_LESSON_RECORDING);
        TOOLTIPS.put(STRUCTURED_EVICTION, Tooltips.COMPACTION_STRUCTURED_EVICTION);
    }

    private final List<String> order = new ArrayList<>(LABELS.keySet());
    private final Map<String, Checkbox> checkboxes = new LinkedHashMap<>();
    private final VerticalLayout rows = new VerticalLayout();

    public CompactionStrategyList() {
        setPadding(false);
        setSpacing(false);
        // starts with nothing checked ("unset") rather than defaulting tool_result_mask to checked
        // here: a per-task override reading "nothing checked" must mean "inherit the run's own
        // setting", not "force tool_result_mask only, overriding whatever the run actually has" -
        // callers that want a visible default checked (job/experiment creation, with no run-level
        // value to inherit from) call setValue(List.of(TOOL_RESULT_MASK)) explicitly instead.
        for (final var name : LABELS.keySet()) {
            final var box = new Checkbox(LABELS.get(name));
            box.setTooltipText(TOOLTIPS.get(name));
            checkboxes.put(name, box);
        }
        rows.setPadding(false);
        rows.setSpacing(false);
        add(rows);
        render();
    }

    private void render() {
        rows.removeAll();
        for (int i = 0; i < order.size(); i++) {
            final String name = order.get(i);
            final int index = i;
            final var row = new HorizontalLayout(checkboxes.get(name));
            row.setAlignItems(Alignment.CENTER);
            final var up = new Button(VaadinIcon.ARROW_UP.create(), e -> move(index, -1));
            up.getElement().setAttribute("aria-label", "move " + LABELS.get(name) + " up");
            up.setEnabled(index > 0);
            final var down = new Button(VaadinIcon.ARROW_DOWN.create(), e -> move(index, 1));
            down.getElement().setAttribute("aria-label", "move " + LABELS.get(name) + " down");
            down.setEnabled(index < order.size() - 1);
            row.add(up, down);
            rows.add(row);
        }
    }

    private void move(final int index, final int delta) {
        final int target = index + delta;
        if (target < 0 || target >= order.size()) return;
        final String a = order.get(index), b = order.get(target);
        order.set(index, b);
        order.set(target, a);
        render();
    }

    /** the enabled rows' names, in their current order - empty (never null) when nothing is enabled. */
    public List<String> getValue() {
        return order.stream().filter(n -> checkboxes.get(n).getValue()).toList();
    }

    /** pre-fills from an existing enabled-and-ordered list (a task override, or a run's own
     *  default): those names move to the front in the given order and are checked; every other
     *  known strategy stays unchecked, in whatever relative order it was already in. Unknown names
     *  (a config value this UI version doesn't recognize) are silently ignored rather than crashing
     *  the form. */
    public void setValue(final List<String> enabledInOrder) {
        if (enabledInOrder == null || enabledInOrder.isEmpty()) return;
        final List<String> newOrder = new ArrayList<>();
        for (final String name : enabledInOrder) if (LABELS.containsKey(name) && !newOrder.contains(name)) newOrder.add(name);
        for (final String name : order) if (!newOrder.contains(name)) newOrder.add(name);
        order.clear();
        order.addAll(newOrder);
        for (final String name : LABELS.keySet()) checkboxes.get(name).setValue(enabledInOrder.contains(name));
        render();
    }
}
