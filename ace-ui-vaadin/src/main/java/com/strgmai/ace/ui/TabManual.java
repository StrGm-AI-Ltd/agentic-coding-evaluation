package com.strgmai.ace.ui;

import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** #474: a short "what this tab does" banner, shown at the top of every tab except Info (which IS
 *  the manual's own landing page - see InfoView). "Full manual" opens the matching wiki page as
 *  rendered markdown in a side panel docked to the right edge.
 *
 *  Not a literal iframe of the wiki: GitHub sends "x-frame-options: deny" and
 *  "frame-ancestors 'none'" on wiki pages (confirmed live) - there is no way to embed the real
 *  page. Instead this fetches the page's raw markdown directly from raw.githubusercontent.com and
 *  renders it through the same Markdown/md-body pipeline FileViewerView already uses, for a
 *  consistent look. Relative image paths (every wiki image is written as "images/foo.png") are
 *  rewritten to absolute raw-content URLs first, or they'd 404 against this app's own origin.
 *  Known gap: in-page links to OTHER wiki pages (e.g. "see Architecture") are not rewritten and
 *  will mis-navigate if clicked from inside the panel - "Open in GitHub" is the correct escape
 *  hatch for that until this is worth the extra regex.
 *
 *  ace-ui-vaadin is otherwise a pure REST/SSE client of ace-service only (see Architecture.md) -
 *  this is a deliberate, narrowly-scoped exception: a documentation fetch has nothing to do with
 *  the benchmark domain ace-service owns, and a new ace-service endpoint just to proxy a GitHub
 *  fetch would be the wrong place to put it.
 *
 *  <p>Its positioning CSS (./styles/manual-panel.css) is imported on MainLayout, not here - every
 *  view this banner appears on is already wrapped by MainLayout, which is guaranteed to actually
 *  be instantiated (unlike this plain static-method class, which Vaadin's annotation scanner may
 *  not treat the same way a real Component gets treated for frontend-resource bundling). */
final class TabManual {
    private static final Logger log = LoggerFactory.getLogger(TabManual.class);
    private static final String REPO = "StrGm-AI-Ltd/agentic-coding-evaluation";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/wiki/" + REPO + "/";
    private static final String WIKI_BASE = "https://github.com/" + REPO + "/wiki/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private TabManual() {
    }

    /** @param wikiPage the wiki page's bare name, e.g. "User-Manual-Jobs" (no ".md", no slashes). */
    static Div banner(final String description, final String wikiPage) {
        final var text = new Span(description);
        text.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final var full = new Button("Full manual", VaadinIcon.BOOK.create(), e -> openPanel(wikiPage));
        full.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        final var row = new HorizontalLayout(text, full);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.setSpacing(true);
        row.setPadding(false);

        final var wrapper = new Div(row);
        wrapper.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("padding", "8px 14px")
                .set("margin-bottom", "16px");
        return wrapper;
    }

    private static void openPanel(final String wikiPage) {
        final var dialog = new Dialog();
        dialog.addClassName("manual-side-panel");
        dialog.setWidth("520px");
        dialog.setCloseOnEsc(true);
        dialog.setCloseOnOutsideClick(true);
        dialog.setDraggable(false);
        dialog.setResizable(false);

        final var title = new Span(wikiPage.replace('-', ' '));
        title.getStyle().set("font-weight", "600");
        final var openInGithub = new Anchor(WIKI_BASE + wikiPage, "Open in GitHub");
        openInGithub.setTarget("_blank");
        final var close = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> dialog.close());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        final var header = new HorizontalLayout(title, openInGithub, close);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        header.setWidthFull();
        header.addClassName("manual-side-panel-header");
        dialog.add(header);

        final var body = new Div();
        body.addClassName("manual-side-panel-body");
        body.setText("Loading…");
        dialog.add(body);
        dialog.open();

        final var ui = UI.getCurrent();
        final var thread = new Thread(() -> loadInto(ui, body, wikiPage), "wiki-manual-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loadInto(final UI ui, final Div body, final String wikiPage) {
        try {
            final var req = HttpRequest.newBuilder(URI.create(RAW_BASE + wikiPage + ".md"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            final var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                ui.access(() -> { body.removeAll(); body.setText("Could not load the manual (HTTP " + resp.statusCode() + ")."); });
                return;
            }
            final var markdown = resp.body().replace("](images/", "](" + RAW_BASE + "images/");
            final var html = "<div class=\"md-body\">" + Markdown.toHtml(markdown) + "</div>";
            ui.access(() -> { body.removeAll(); body.add(new Html(html)); });
        } catch (final Exception e) {
            log.warn("could not load wiki page {}: {}", wikiPage, e.toString());
            final var message = e.toString();
            ui.access(() -> { body.removeAll(); body.setText("Could not load the manual: " + message); });
        }
    }
}
