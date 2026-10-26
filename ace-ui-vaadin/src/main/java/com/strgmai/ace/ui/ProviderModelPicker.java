package com.strgmai.ace.ui;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Cascading "pick a provider, then a model from its own live list" control - the shared
 *  replacement for every model-selection field in the UI (JobNewView/ExperimentNewView's
 *  model/reviewer/judge fields, JobDetailView's own restart-tab judge field), now that the LLM
 *  Providers registry makes "which providers exist" a real, live data source instead of a
 *  hardcoded list. Mirrors CompactionStrategyList's own precedent: a plain getValue()/setValue(),
 *  no HasValue, slots into Forms.row(...) like any other field.
 *
 *  Wire format: a bare model id when Local is selected (the existing "no provider/ prefix means
 *  local" convention used throughout ace-service - see ProviderStore.route()/resolveModel()), or
 *  "providerKey/model" otherwise. Blank/null means nothing is selected - a legal, distinct state
 *  for the optional reviewer/judge fields (and, for judge fields specifically, the existing
 *  "blank = inherit the run's own model" meaning - this control never forces a selection).
 *
 *  The provider list is loaded eagerly in the constructor (a fast, local DB read, unlike the
 *  model list) rather than attach-deferred like every other view's own loadSuggestions() - so a
 *  caller can call setValue(...) for pre-fill immediately after construction, before the
 *  component is ever attached to a live page (JobDetailView.buildRestartTab()'s own existing
 *  pattern), and still have it resolve a real provider match. */
public class ProviderModelPicker extends HorizontalLayout {
    private static final Logger log = LoggerFactory.getLogger(ProviderModelPicker.class);

    /** provider_key == null marks this synthetic entry - checked by isLocal(), never sent to the backend. */
    private static final Api.Provider LOCAL = new Api.Provider("local", "Local", null, null, null, false);

    private final ServiceClient client;
    private final ComboBox<Api.Provider> provider = new ComboBox<>();
    private final ComboBox<String> model = new ComboBox<>();
    private final Span error = new Span();
    private List<String> pastRunModels = List.of();   // merged into the Local model list - see withPastRunHistory()
    // ProviderStore.route() (the main model field's own convention - JobNewView.model,
    // ExperimentNewView.model/modelA/modelB) treats a BARE string as local, no prefix needed. The
    // reviewer/judge model fields go through the older, separate ProviderStore.resolveModel()
    // instead, which reserves a bare string for a provider-key shorthand (e.g. typing "nebius")
    // and needs an explicit "omlx/" prefix to mean "a specific local model" - see
    // useOmlxPrefixForLocal() for those fields' own wire convention.
    private boolean omlxPrefixedLocal = false;

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

    /** Opts this picker's Local branch into merging past-run model history on top of the live
     *  model-server list - the perk the 3 main-model fields had before conversion to this
     *  component; skipped by default (reviewer/judge fields never had it). Call before the
     *  operator can plausibly have picked Local already (right after construction, same as every
     *  other field's own loadSuggestions() timing) - it does not retroactively refresh an
     *  already-populated model list. */
    public ProviderModelPicker withPastRunHistory(final List<String> models) {
        this.pastRunModels = models;
        return this;
    }

    /** Switches this picker's wire convention to match ProviderStore.resolveModel() (the reviewer/
     *  judge model fields) instead of ProviderStore.route() (the main model fields' own default):
     *  Local + a model now submits "omlx/" + model rather than the bare model id, and setValue()
     *  recognizes an incoming "omlx/..." string as Local too. */
    public ProviderModelPicker useOmlxPrefixForLocal() {
        this.omlxPrefixedLocal = true;
        return this;
    }

    private void loadProviders() {
        try {
            final var items = new ArrayList<Api.Provider>();
            items.add(LOCAL);
            items.addAll(client.providers());
            provider.setItems(items);
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
            if (isLocal(p)) {
                final var items = new LinkedHashSet<>(client.models());
                items.addAll(pastRunModels);
                model.setItems(items);
            } else {
                model.setItems(client.providerModels(p.id()));
            }
            model.setEnabled(true);
        } catch (final Exception e) {
            log.warn("could not load models for provider {}: {}", p.name(), e.toString());
            model.setEnabled(false);
            model.setItems(List.of());
            error.setText("could not load models for " + p.name() + ": " + client.errorText(e));
            error.setVisible(true);
        }
    }

    private static boolean isLocal(final Api.Provider p) {
        return p.provider_key() == null;
    }

    /** The final wire string - bare model id for Local, "providerKey/model" otherwise; blank when
     *  nothing is selected (a legal state - see the class javadoc). */
    public String getValue() {
        final var p = provider.getValue();
        final var m = model.getValue();
        if (p == null || m == null || m.isBlank()) return "";
        if (isLocal(p)) return omlxPrefixedLocal ? "omlx/" + m : m;
        return p.provider_key() + "/" + m;
    }

    /** Parses an existing wire string back into provider+model selection, for pre-fill. Blank/null
     *  leaves both unselected. An unrecognized provider prefix (e.g. a deleted provider) falls back
     *  to Local with the raw string as the model - tolerant, matching ProviderStore.route()'s own
     *  "an unknown prefix just stays a local model id" convention. */
    public void setValue(final String wireValue) {
        if (wireValue == null || wireValue.isBlank()) return;
        final int slash = wireValue.indexOf('/');
        if (slash < 0) {
            // a bare string: under the omlx-prefixed convention this isn't a legal "specific local
            // model" shape at all (resolveModel() would treat it as an unrecognized provider key
            // and throw) - shown as Local anyway, best-effort, same tolerant fallback as elsewhere.
            selectProviderAndModel(LOCAL, wireValue);
            return;
        }
        final var prefix = wireValue.substring(0, slash);
        final var rest = wireValue.substring(slash + 1);
        if (omlxPrefixedLocal && "omlx".equals(prefix)) {
            selectProviderAndModel(LOCAL, rest);
            return;
        }
        final var match = provider.getGenericDataView().getItems()
                .filter(p -> prefix.equals(p.provider_key())).findFirst();
        selectProviderAndModel(match.orElse(LOCAL), match.isPresent() ? rest : wireValue);
    }

    /** provider.setValue(...) fires onProviderChanged() synchronously, which already populates
     *  (or, on a fetch failure, disables/empties) the model combo - addModelItemIfMissing() then
     *  guarantees THIS value is selectable either way, so a pre-fill always sticks even when the
     *  live list doesn't happen to include it (a now-retired model, or the fetch failed outright). */
    private void selectProviderAndModel(final Api.Provider p, final String modelValue) {
        provider.setValue(p);
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
