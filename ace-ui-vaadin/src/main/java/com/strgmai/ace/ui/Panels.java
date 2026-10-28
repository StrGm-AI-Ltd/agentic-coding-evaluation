package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Div;

/** Inline callout panels and mono blocks (the .callout / pre styles of the original UI). */
public final class Panels {

    private Panels() {
    }

    public static Div error(final String text) {
        return panel(text, "var(--lumo-error-color)", "var(--lumo-error-color-10pct)");
    }

    public static Div warn(final String text) {
        return panel(text, "var(--lumo-warning-color)", "var(--lumo-warning-color-10pct)");
    }

    /** A callout container (same chrome as warn/error) holding arbitrary children (C-5). */
    public static Div callout(final String edgeColor, final String backgroundColor, final com.vaadin.flow.component.Component... children) {
        final var div = new Div();
        div.add(children);
        div.getStyle()
                .set("border-left", "4px solid " + edgeColor)
                .set("background", backgroundColor)
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

    /** #476 (PR 3/3): the shared chrome both statTile() overloads below sit on - background,
     *  radius, padding, and the uppercase label line; just missing the value (string or
     *  live-mutable component) that makes a label alone into an actual stat. */
    private static Div tileShell(final String label) {
        final var labelSpan = new com.vaadin.flow.component.html.Span(label);
        labelSpan.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.8em")
                .set("text-transform", "uppercase").set("letter-spacing", "0.03em");
        final var tile = new Div(labelSpan);
        tile.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "2px")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
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
        valueSpan.getStyle().set("font-size", "1.4em").set("font-weight", "600");
        tile.add(valueSpan);
        if (detail != null && !detail.isBlank()) {
            final var detailSpan = new com.vaadin.flow.component.html.Span(detail);
            detailSpan.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.8em");
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

    /** #476 (PR 3/3): a boxed card wrapper for an existing section - same sectionTitle() header,
     *  now with the "night operator" mockup's panel chrome (border, radius, background) around its
     *  content instead of a bare H3 sitting directly on the page background. */
    public static Div card(final String title, final com.vaadin.flow.component.Component... children) {
        final var card = new Div(sectionTitle(title));
        card.add(children);
        // VerticalLayout (both JobDetailView/RunDetailView themselves, and liveSection) does NOT
        // stretch a plain child Div to its own width by default - width:100%+box-sizing is what
        // keeps a wrapped Grid from collapsing to its own min-content width instead of filling the
        // card (found live: planGrid rendered as a ~90px sliver until this was added).
        card.getStyle().set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-l)")
                .set("padding", "12px 16px")
                .set("margin", "10px 0")
                .set("width", "100%")
                .set("box-sizing", "border-box");
        return card;
    }

    private static Div panel(final String text, final String edge, final String background) {
        final var div = new Div();
        div.setText(text);
        div.getStyle()
                .set("border-left", "4px solid " + edge)
                .set("background", background)
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
                .set("background", "var(--lumo-contrast-10pct)")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("overflow-x", "auto")
                .set("white-space", "pre-wrap")
                .set("margin", "6px 0");
        return div;
    }
}
