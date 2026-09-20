package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Div;

/** Inline callout panels and mono blocks (the .callout / pre styles of the original UI). */
public final class Panels {

    private Panels() {
    }

    public static Div error(String text) {
        return panel(text, "var(--lumo-error-color)", "var(--lumo-error-color-10pct)");
    }

    public static Div warn(String text) {
        return panel(text, "var(--lumo-warning-color)", "var(--lumo-warning-color-10pct)");
    }

    /** A callout container (same chrome as warn/error) holding arbitrary children (C-5). */
    public static Div callout(String edgeColor, String backgroundColor, com.vaadin.flow.component.Component... children) {
        Div div = new Div();
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
    public static com.vaadin.flow.component.html.H3 sectionTitle(String title) {
        com.vaadin.flow.component.html.H3 header = new com.vaadin.flow.component.html.H3(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        return header;
    }

    private static Div panel(String text, String edge, String background) {
        Div div = new Div();
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
    public static Div mono(String text) {
        Div div = new Div();
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
