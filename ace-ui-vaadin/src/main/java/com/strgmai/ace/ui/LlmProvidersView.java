package com.strgmai.ace.ui;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** LLM Providers — the UI twin of GET/POST/PUT/DELETE /api/providers. Lets an operator manage the
 *  registry that replaced the old hardcoded ProviderConfig enum: name, the stable "provider/model"
 *  prefix (provider_key), base URL, API key, and an optional default model (so a bare provider key
 *  alone, e.g. just "nebius", resolves to something runnable - see ProviderStore.resolveModel()).
 *  The edit dialog's API key field always starts blank, even for a provider that already has one
 *  configured — the backend never serializes the real value back (BenchController.providerRow()),
 *  so there is nothing to pre-fill; leaving it blank on Save means "keep the existing key". */
@Route(value = "providers", layout = MainLayout.class)
public class LlmProvidersView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(LlmProvidersView.class);

    private final ServiceClient client;
    private final Grid<Api.Provider> grid = new Grid<>(Api.Provider.class, false);
    private final Span error = new Span();
    // #517: "checking"/"reachable"/"unreachable" per provider id, filled in live as each
    // background check completes - absent (not yet checked) renders the same as "checking".
    private final Map<String, String> reachability = new ConcurrentHashMap<>();

    public LlmProvidersView(final ServiceClient client) {
        this.client = client;
        setPadding(true);

        final var addProvider = new Button("Add provider", VaadinIcon.PLUS.create(), e -> openEditDialog(null));
        error.getStyle().set("color", "var(--lumo-error-color)");

        grid.addColumn(Api.Provider::name).setHeader("name").setAutoWidth(true);
        grid.addColumn(Api.Provider::provider_key).setHeader("provider key").setAutoWidth(true);
        grid.addColumn(Api.Provider::base_url).setHeader("base URL").setAutoWidth(true).setFlexGrow(1);
        grid.addColumn(p -> p.default_model() == null || p.default_model().isBlank() ? "" : p.default_model())
                .setHeader("default model").setAutoWidth(true);
        grid.addColumn(p -> p.has_api_key() ? "configured" : "not set").setHeader("API key").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::statusBadge)).setHeader("status").setAutoWidth(true);
        grid.addColumn(new ComponentRenderer<>(this::actions)).setHeader("").setAutoWidth(true).setFlexGrow(0);
        grid.getColumns().forEach(c -> c.setResizable(true));

        add(TabManual.banner(this, "Configure which models ACE can target.", "User-Manual-Providers"));
        add(new H2("LLM Providers"), new HorizontalLayout(addProvider, error), grid);
        setSizeFull();
        expand(grid);

        addAttachListener(e -> load());
    }

    private HorizontalLayout actions(final Api.Provider p) {
        final var edit = new Button(VaadinIcon.EDIT.create(), e -> openEditDialog(p));
        edit.getElement().setAttribute("aria-label", "edit " + p.name());
        final var delete = new Button(VaadinIcon.TRASH.create(), e -> openDeleteConfirm(p));
        delete.getElement().setAttribute("aria-label", "delete " + p.name());
        return new HorizontalLayout(edit, delete);
    }

    /** #517: GET /api/providers/{id}/models already builds a real OpenAI-compatible client against
     *  the provider's own base_url/key and throws with a specific message on failure - reused here
     *  as-is rather than adding a second check. Deliberately NOT /health (PR3's endpoint, #476): an
     *  oMLX-style /health path is a local convention, not something hosted providers (Nebius,
     *  OpenAI, Anthropic, ...) expose, while every registered provider is already built as an
     *  OpenAI client (see providerModels() in BenchController), making /models the one endpoint
     *  guaranteed meaningful across all of them. */
    private com.vaadin.flow.component.badge.Badge statusBadge(final Api.Provider p) {
        final var state = reachability.getOrDefault(p.id(), "checking");
        return switch (state) {
            case "reachable" -> Badges.text("reachable", Badges.SUCCESS);
            case "unreachable" -> Badges.text("unreachable", Badges.ERROR);
            default -> Badges.text("checking…", Badges.CONTRAST);
        };
    }

    private void load() {
        error.setText("");
        try {
            final List<Api.Provider> providers = client.providers();
            grid.setItems(providers);
            checkReachability(providers);
        } catch (final Exception e) {
            log.warn("could not load providers: {}", e.toString());
            error.setText(client.errorText(e));
        }
    }

    /** One short-lived background thread per provider, run in parallel so N providers cost one
     *  round trip's worth of wall time, not N - this is a per-visit check (fired once when the page
     *  loads), not wired into any recurring poll, so it never hammers a rate-limited hosted
     *  provider's API in the background while nobody is looking at this page. */
    private void checkReachability(final List<Api.Provider> providers) {
        final var ui = UI.getCurrent();
        for (final var p : providers) {
            reachability.put(p.id(), "checking");
            new Thread(() -> {
                String result;
                try {
                    client.providerModels(p.id());
                    result = "reachable";
                } catch (final Exception e) {
                    log.debug("provider {} reachability check failed: {}", p.id(), e.toString());
                    result = "unreachable";
                }
                final var finalResult = result;
                ui.access(() -> {
                    reachability.put(p.id(), finalResult);
                    grid.getDataProvider().refreshItem(p);
                });
            }, "provider-reachability-" + p.id()).start();
        }
    }

    /** `existing == null` means "add a new provider" - otherwise the dialog opens pre-filled for
     *  editing it. The API key field is always blank either way (see the class javadoc). */
    private void openEditDialog(final Api.Provider existing) {
        final var dialog = new Dialog();
        dialog.setHeaderTitle(existing == null ? "Add provider" : "Edit " + existing.name());
        dialog.setWidth("min(500px, 90vw)");

        final var name = new TextField("Name");
        final var providerKey = new TextField("Provider key");
        providerKey.setHelperText("the stable \"provider/model\" prefix, e.g. \"nebius\"");
        final var baseUrl = new TextField("Base URL");
        final var apiKey = new PasswordField("API key");
        apiKey.setPlaceholder(existing != null && existing.has_api_key() ? "configured - leave blank to keep it" : "none set");
        final var defaultModel = new TextField("Default model");
        defaultModel.setHelperText("optional - lets a bare provider key alone resolve to a real model");
        if (existing != null) {
            name.setValue(existing.name());
            providerKey.setValue(existing.provider_key());
            baseUrl.setValue(existing.base_url());
            if (existing.default_model() != null) defaultModel.setValue(existing.default_model());
        }

        final var dialogError = new Span();
        dialogError.getStyle().set("color", "var(--lumo-error-color)");

        final var save = new Button("Save", e -> {
            try {
                if (existing == null) {
                    client.createProvider(name.getValue(), providerKey.getValue(), baseUrl.getValue(),
                            apiKey.getValue().isBlank() ? null : apiKey.getValue(), defaultModel.getValue());
                } else {
                    client.updateProvider(existing.id(), name.getValue(), providerKey.getValue(), baseUrl.getValue(),
                            apiKey.getValue().isBlank() ? null : apiKey.getValue(), defaultModel.getValue());
                }
                dialog.close();
                load();
            } catch (final Exception ex) {
                log.warn("could not save provider: {}", ex.toString());
                dialogError.setText(client.errorText(ex));
            }
        });
        save.getStyle().set("margin-top", "8px");
        final var cancel = new Button("Cancel", e -> dialog.close());

        dialog.add(new VerticalLayout(name, providerKey, baseUrl, apiKey, defaultModel, dialogError));
        dialog.getFooter().add(cancel, save);
        dialog.open();
    }

    private void openDeleteConfirm(final Api.Provider p) {
        final var dialog = new Dialog();
        dialog.setHeaderTitle("Delete " + p.name() + "?");
        dialog.add(new Span("This removes the provider from the registry. Any run already using it keeps working " +
                "(its own job config carries a frozen model string, not a live reference back here)."));
        final var confirmError = new Span();
        confirmError.getStyle().set("color", "var(--lumo-error-color)");
        dialog.add(confirmError);
        final var delete = new Button("Delete", e -> {
            try {
                client.deleteProvider(p.id());
                dialog.close();
                load();
            } catch (final Exception ex) {
                log.warn("could not delete provider {}: {}", p.id(), ex.toString());
                confirmError.setText(client.errorText(ex));
            }
        });
        delete.getStyle().set("color", "var(--lumo-error-text-color)");
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), delete);
        dialog.open();
    }
}
