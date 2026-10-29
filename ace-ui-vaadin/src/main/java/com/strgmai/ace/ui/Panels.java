package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Div;

/** Inline callout panels and mono blocks (the .callout / pre styles of the original UI).
 *
 *  #529: Aura migration - every var(--lumo-*) color reference these helpers used to carry
 *  (error/warning/success edge+background tinting on panel()/callout(), the secondary-text/
 *  contrast-5pct/border tokens on tileShell()/card()) is removed outright rather than retargeted
 *  to an Aura equivalent, per the operator's own explicit call: drop the custom coloring, accept
 *  these render with Aura's own plain/flat defaults. error()/warn()/callout() no longer take a
 *  color - there is nothing left to parameterize. */
public final class Panels {

    private Panels() {
    }

    public static Div error(final String text) {
        return panel(text);
    }

    public static Div warn(final String text) {
        return panel(text);
    }

    /** A callout container (same chrome as warn/error) holding arbitrary children (C-5). */
    public static Div callout(final com.vaadin.flow.component.Component... children) {
        final var div = new Div();
        div.add(children);
        div.getStyle()
                .set("padding", "8px 12px")
                .set("border-radius", "4px")
                .set("margin", "6px 0");
        return div;
    }

    /** The one section-title helper (m15): consistent H3 rhythm across the app. */
    public static com.vaadin.flow.component.html.H3 sectionTitle(final String title) {
        final var header = new com.vaadin.flow.component.html.H3(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        return header;
    }

    /** #476 (PR 3/3): the shared chrome both statTile() overloads below sit on - just missing the
     *  value (string or live-mutable component) that makes a label alone into an actual stat. */
    private static Div tileShell(final String label) {
        final var labelSpan = new com.vaadin.flow.component.html.Span(label);
        labelSpan.getStyle().set("font-size", "0.8em")
                .set("text-transform", "uppercase").set("letter-spacing", "0.03em");
        final var tile = new Div(labelSpan);
        tile.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "2px")
                .set("padding", "10px 14px")
                .set("min-width", "140px");
        return tile;
    }

    /** #476 (PR 3/3): a boxed "big number + label + detail" stat, replacing a bare inline Span
     *  wherever one of those three lines was the point of the row (RunDetailView's own former
     *  score() helper) - a static snapshot value, never mutated in place after being built. */
    public static Div statTile(final String label, final String value, final String detail) {
        final var tile = tileShell(label);
        final var valueSpan = new com.vaadin.flow.component.html.Span(value);
        valueSpan.getStyle().set("font-size", "1.4em").set("font-weight", "600")
                // #551: a long value (e.g. a remote provider's full model name) wrapped across
                // multiple lines instead, ballooning the tile's height past its row-mates - force
                // single-line + ellipsis instead, with the full value still reachable via a tooltip.
                .set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap");
        valueSpan.getElement().setAttribute("title", value);
        tile.add(valueSpan);
        if (detail != null && !detail.isBlank()) {
            final var detailSpan = new com.vaadin.flow.component.html.Span(detail);
            detailSpan.getStyle().set("font-size", "0.8em");
            tile.add(detailSpan);
        }
        return tile;
    }

    /** #476 (PR 3/3): same tile chrome, but the value slot is a component built ONCE by the
     *  caller and mutated in place afterward (JobDetailView's own stepLine/activeTimeLine fields,
     *  updated via setText() on every 2s poll) - rebuilding a String-valued tile from scratch each
     *  poll would otherwise steal focus/selection the same way a full-DOM rebuild would. */
    public static Div statTile(final String label, final com.vaadin.flow.component.Component value) {
        final var tile = tileShell(label);
        tile.add(value);
        return tile;
    }

    /** #559: statTile() plus a visible border/surface/radius, via real Aura/base tokens
     *  (--vaadin-border-color-secondary, --vaadin-radius-l, --vaadin-background-container) rather
     *  than hand-picked colors - the "Vaadin Dashboard" tile look (vaadin.com/docs/latest/
     *  components/dashboard) the redesigned Job detail page's own status row already established.
     *  Promoted out of JobDetailPrototypeView's own former private dashboardTile() helper
     *  (verbatim chrome) so the Stats tab's redesign (#559) can share the identical tile look
     *  rather than reinventing it - deliberately a SEPARATE helper from the bare statTile() above,
     *  not a parameter on it: statTile() is still used elsewhere (JobDetailView's own live
     *  stepLine/activeTimeLine tiles) with no border/background chrome at all (#529), and giving it
     *  one there would have silently changed those too. */
    public static Div dashboardTile(final String label, final String value) {
        return dashboardTile(label, value, null);
    }

    /** dashboardTile() with an optional third, smaller detail line underneath the value - e.g.
     *  "9 tests, 0 failed" under a "harness verification" tile's "GREEN" value. */
    public static Div dashboardTile(final String label, final String value, final String detail) {
        final var tile = statTile(label, value, detail);
        tile.getStyle()
                .set("border", "1px solid var(--vaadin-border-color-secondary)")
                .set("border-radius", "var(--vaadin-radius-l)")
                .set("background", "var(--vaadin-background-container)")
                .set("padding", "8px 10px")
                .set("min-width", "0");
        final var children = tile.getChildren().toList();
        if (children.size() > 1) children.get(1).getElement().getStyle().set("font-size", "1.05em");
        return tile;
    }

    /** #476 (PR 3/3): a boxed card wrapper for an existing section - same sectionTitle() header
     *  as before, now with no border/background chrome around its content (#529: Aura migration).
     */
    public static Div card(final String title, final com.vaadin.flow.component.Component... children) {
        final var card = new Div(sectionTitle(title));
        card.add(children);
        // VerticalLayout (both JobDetailView/RunDetailView themselves, and liveSection) does NOT
        // stretch a plain child Div to its own width by default - width:100%+box-sizing is what
        // keeps a wrapped Grid from collapsing to its own min-content width instead of filling the
        // card (found live: planGrid rendered as a ~90px sliver until this was added). Still needed
        // regardless of the border/background chrome removed above.
        card.getStyle().set("padding", "12px 16px")
                .set("margin", "10px 0")
                .set("width", "100%")
                .set("box-sizing", "border-box");
        return card;
    }

    private static Div panel(final String text) {
        final var div = new Div();
        div.setText(text);
        div.getStyle()
                .set("padding", "8px 12px")
                .set("border-radius", "4px")
                .set("margin", "6px 0")
                .set("white-space", "pre-wrap");
        return div;
    }

    /** Monospaced block, like a <pre>. */
    public static Div mono(final String text) {
        final var div = new Div();
        div.setText(text == null || text.isBlank() ? "–" : text);
        div.getStyle()
                .set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace")
                .set("font-size", "12px")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("overflow-x", "auto")
                .set("white-space", "pre-wrap")
                .set("margin", "6px 0");
        return div;
    }
}
