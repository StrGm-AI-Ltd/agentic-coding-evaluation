package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** #474/#485: a short "what this tab does" banner, shown at the top of every tab except Info
 *  (which IS the manual's own landing page - see InfoView). "Full manual" opens the matching wiki
 *  page as rendered markdown in a side pane - a real MainLayout.split (SplitLayout) secondary
 *  pane with a draggable divider, not a floating overlay, so its width is operator-adjustable
 *  (#485 - the original #474 dialog-overlay version had a fixed width).
 *
 *  Not a literal iframe of the wiki: GitHub sends "x-frame-options: deny" and
 *  "frame-ancestors 'none'" on wiki pages (confirmed live) - there is no way to embed the real
 *  page. Instead this fetches the page's raw markdown directly from raw.githubusercontent.com and
 *  renders it through the same Markdown/md-body pipeline FileViewerView already uses (that view's
 *  own @CssImport("./styles/md-viewer.css") is what actually styles ".md-body" - Flow's frontend
 *  bundle is app-wide, not per-route, so nothing extra is needed here), for a consistent look.
 *  Relative image paths (every wiki image is written as "images/foo.png") are rewritten to
 *  absolute raw-content URLs first, or they'd 404 against this app's own origin. Known gap:
 *  in-page links to OTHER wiki pages (e.g. "see Architecture") are not rewritten and will
 *  mis-navigate if clicked from inside the pane - "Open in GitHub" is the correct escape hatch
 *  for that until this is worth the extra regex.
 *
 *  ace-ui-vaadin is otherwise a pure REST/SSE client of ace-service only (see Architecture.md) -
 *  this is a deliberate, narrowly-scoped exception: a documentation fetch has nothing to do with
 *  the benchmark domain ace-service owns, and a new ace-service endpoint just to proxy a GitHub
 *  fetch would be the wrong place to put it. */
final class TabManual {
    private static final Logger log = LoggerFactory.getLogger(TabManual.class);
    private static final String REPO = "StrGm-AI-Ltd/agentic-coding-evaluation";
    private static final String RAW_BASE = "https://raw.githubusercontent.com/wiki/" + REPO + "/";
    private static final String WIKI_BASE = "https://github.com/" + REPO + "/wiki/";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private TabManual() {
    }

    /** @param view the tab's own root component - used only to find its owning MainLayout, lazily,
     *              at click time (the view isn't attached yet when banner() itself runs, since
     *              every call site builds this banner from its own constructor).
     *  @param wikiPage the wiki page's bare name, e.g. "User-Manual-Jobs" (no ".md", no slashes). */
    static Div banner(final Component view, final String description, final String wikiPage) {
        final var text = new Span(description);
        text.getStyle().set("color", "var(--lumo-secondary-text-color)");

        final var full = new Button("Full manual", VaadinIcon.BOOK.create(), e -> ownerLayout(view).openManual(wikiPage));
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

    /** Walks the COMPONENT tree (not just the immediate parent) up from a tab's own root - Flow
     *  keeps Component.getParent() in sync with element-level appendChild, which is how
     *  MainLayout.showRouterLayoutContent() attaches each routed view (see its own comment), so
     *  this reaches MainLayout regardless of how many plain Divs sit in between. */
    private static MainLayout ownerLayout(final Component view) {
        var c = view.getParent();
        while (c.isPresent() && !(c.get() instanceof MainLayout)) {
            c = c.get().getParent();
        }
        return (MainLayout) c.orElseThrow(() -> new IllegalStateException(view + " is not attached under a MainLayout"));
    }

    /** Builds the pane's own header (title, Open in GitHub, close) and a growing/scrolling body,
     *  into the container MainLayout.openManual() just revealed, then kicks off the async fetch.
     *  Mirrors FileViewerView's own "flex-grow:1 + overflow:auto, then expand()" markdown-body
     *  pattern exactly, for the same reason: a fixed-height pane with its OWN internal scroll,
     *  independent of the main content pane's scroll position. */
    static void renderInto(final VerticalLayout container, final String wikiPage, final Runnable onClose) {
        container.removeAll();

        final var title = new Span(wikiPage.replace('-', ' '));
        title.getStyle().set("font-weight", "600");
        final var openInGithub = new Anchor(WIKI_BASE + wikiPage, "Open in GitHub");
        openInGithub.setTarget("_blank");
        final var close = new Button(VaadinIcon.CLOSE_SMALL.create(), e -> onClose.run());
        close.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        final var header = new HorizontalLayout(title, openInGithub, close);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        header.setWidthFull();
        header.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("padding-bottom", "8px");
        container.add(header);

        final var body = new Div();
        body.setText("Loading…");
        // #486, found live: flex-grow/overflow alone left this Div at its own unconstrained
        // content width (~1600px for a real wiki page) instead of the pane's actual, resizable
        // width - manualSlot itself correctly shrank to match the pane, but this child never
        // did, so it just rendered wide and got silently clipped rather than reflowing (the
        // same root cause broke embedded images "scaling" to the pane: max-width:100% has
        // nothing to constrain against when the box it's 100% OF was never width-constrained
        // itself). width:100% is what was missing.
        body.getStyle().set("flex-grow", "1").set("overflow", "auto").set("width", "100%");
        container.add(body);
        container.expand(body);

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
