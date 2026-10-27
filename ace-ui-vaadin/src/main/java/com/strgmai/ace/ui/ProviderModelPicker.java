package com.strgmai.ace.ui;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Cascading "pick a provider, then a model from its own live list" control - the shared
 *  replacement for every model-selection field in the UI (JobNewView/ExperimentNewView's
 *  model/reviewer/judge fields, JobDetailView's own restart-tab judge field), now that the LLM
 *  Providers registry makes "which providers exist" a real, live data source instead of a
 *  hardcoded list. Mirrors CompactionStrategyList's own precedent: a plain getValue()/setValue(),
 *  no HasValue, slots into Forms.row(...) like any other field.
 *
 *  Every registered provider - including the local model server's own "omlx" row - is treated
 *  uniformly: no synthetic "Local" entry (there used to be one, duplicating the real "omlx" row
 *  once an operator registered it - see #463). Wire format is always "providerKey/model";
 *  blank/null means nothing is selected - a legal, distinct state for the optional reviewer/judge
 *  fields (and, for judge fields specifically, the existing "blank = inherit the run's own model"
 *  meaning - this control never forces a selection). A historical run's own model string may still
 *  be bare (pre-#465 backfill, or a stray manual entry) - setValue() treats a bare string as
 *  "omlx/" + itself, the same "no prefix means the local server" assumption the rest of
 *  ace-service already made before every provider got a real registry row.
 *
 *  If no provider with key "omlx" is registered, there is currently no way to pick a local model
 *  through this control at all - the LLM Providers tab is expected to always carry that row.
 *
 *  The provider list is loaded eagerly in the constructor (a fast, local DB read, unlike the
 *  model list) rather than attach-deferred like every other view's own loadSuggestions() - so a
 *  caller can call setValue(...) for pre-fill immediately after construction, before the
 *  component is ever attached to a live page (JobDetailView.buildRestartTab()'s own existing
 *  pattern), and still have it resolve a real provider match. */
public class ProviderModelPicker extends HorizontalLayout {
    private static final Logger log = LoggerFactory.getLogger(ProviderModelPicker.class);

    private final ServiceClient client;
    private final ComboBox<Api.Provider> provider = new ComboBox<>();
    private final ComboBox<String> model = new ComboBox<>();
    private final Span error = new Span();

    public ProviderModelPicker(final ServiceClient client, final String label) {
        this.client = client;
        // label names the FIELD (e.g. "Reviewer model") - the left combo only picks the provider,
        // so it needs its own, distinct caption rather than inheriting the field's name wholesale
        // (previously made it look like "Reviewer model" itself selected a provider, not a model).
        provider.setLabel(label + " provider");
        provider.setItemLabelGenerator(Api.Provider::name);
        model.setLabel(label);
        model.setAllowCustomValue(true);
        model.setEnabled(false);
        error.getStyle().set("color", "var(--lumo-error-color)").set("font-size", "0.8em");
        error.setVisible(false);

        provider.addValueChangeListener(e -> onProviderChanged());
        setAlignItems(Alignment.END);
        add(provider, model, error);
        loadProviders();
    }

    /** Applies to both halves - what this field means is a property of the pair, not of either combo alone. */
    public ProviderModelPicker withTooltipText(final String text) {
        provider.setTooltipText(text);
        model.setTooltipText(text);
        return this;
    }

    private void loadProviders() {
        try {
            provider.setItems(client.providers());
        } catch (final Exception e) {
            log.warn("could not load providers: {}", e.toString());
            error.setText("could not load providers: " + client.errorText(e));
            error.setVisible(true);
        }
    }

    private void onProviderChanged() {
        model.clear();
        final var p = provider.getValue();
        if (p == null) {
            model.setEnabled(false);
            model.setItems(List.of());
            return;
        }
        error.setVisible(false);
        try {
            final var items = client.providerModels(p.id());
            model.setItems(items);
            model.setEnabled(true);
        } catch (final Exception e) {
            log.warn("could not load models for provider {}: {}", p.name(), e.toString());
            model.setEnabled(false);
            model.setItems(List.of());
            error.setText("could not load models for " + p.name() + ": " + client.errorText(e));
            error.setVisible(true);
        }
    }

    /** The final wire string - "providerKey/model"; blank when nothing is selected (a legal state -
     *  see the class javadoc). */
    public String getValue() {
        final var p = provider.getValue();
        final var m = model.getValue();
        if (p == null || m == null || m.isBlank()) return "";
        return p.provider_key() + "/" + m;
    }

    /** Parses an existing wire string back into provider+model selection, for pre-fill. Blank/null
     *  leaves both unselected. A bare string (no "/") is pre-#465-backfill data or a stray manual
     *  entry - treated as the omlx provider's own model, the same "no prefix means local" rule the
     *  rest of ace-service already used before every provider had a real row. An unrecognized
     *  prefix (e.g. a deleted provider) logs a warning and leaves the picker unselected - there is
     *  no sensible provider to fall back to once "Local" is no longer always present. */
    public void setValue(final String wireValue) {
        if (wireValue == null || wireValue.isBlank()) return;
        final int slash = wireValue.indexOf('/');
        final String key = slash < 0 ? "omlx" : wireValue.substring(0, slash);
        final String modelValue = slash < 0 ? wireValue : wireValue.substring(slash + 1);
        final var match = provider.getGenericDataView().getItems()
                .filter(p -> key.equals(p.provider_key())).findFirst();
        if (match.isEmpty()) {
            log.warn("could not pre-fill \"{}\": no registered provider with key \"{}\"", wireValue, key);
            return;
        }
        // provider.setValue(...) fires onProviderChanged() synchronously, which already populates
        // (or, on a fetch failure, disables/empties) the model combo - addModelItemIfMissing() then
        // guarantees THIS value is selectable either way, so a pre-fill always sticks even when the
        // live list doesn't happen to include it (a now-retired model, or the fetch failed outright).
        provider.setValue(match.get());
        addModelItemIfMissing(modelValue);
        model.setValue(modelValue);
    }

    private void addModelItemIfMissing(final String value) {
        final var current = model.getGenericDataView().getItems().toList();
        if (!current.contains(value)) {
            final var merged = new ArrayList<>(current);
            merged.add(value);
            model.setItems(merged);
        }
        model.setEnabled(true);
    }
}
