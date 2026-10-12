package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;

/** Renders Api.TaskDiff as a read-only, colorized diff plus new-file panels. Every line goes
 *  through Vaadin's own setText() (never raw HTML), so nothing here needs its own escaping - agent
 *  -authored file content can contain anything, including literal HTML/script-looking text, and
 *  stays safe, the same guarantee Panels.mono() already relies on elsewhere in this view. */
final class DiffView {

    private DiffView() {
    }

    static Component render(final Api.TaskDiff diff) {
        final var container = new Div();
        if (!diff.started()) {
            container.add(Panels.mono("(workspace not initialized yet)"));
            return container;
        }
        if (diff.error() != null) {
            container.add(Panels.error(diff.error()));
            return container;
        }
        container.add(caption(diff));
        final boolean hasDiff = diff.diff() != null && !diff.diff().isBlank();
        if (!hasDiff && diff.new_files().isEmpty()) {
            container.add(Panels.mono("(no changes)"));
            return container;
        }
        if (hasDiff) {
            container.add(diffBlock(diff.diff()));
            if (diff.diff_truncated()) container.add(note("the diff was truncated - too large to show in full"));
        }
        for (final Api.NewFile f : diff.new_files()) {
            final var header = new H4(f.path() + " (new file)");
            header.getStyle().set("margin", "12px 0 4px 0").set("font-size", "0.95em");
            container.add(header);
            container.add(Panels.mono(f.content()));
            if (f.truncated()) container.add(note(f.path() + " was truncated - too large to show in full"));
        }
        if (diff.new_files_truncated()) container.add(note("more new files exist than are shown here"));
        return container;
    }

    private static Span caption(final Api.TaskDiff diff) {
        final var text = diff.finished()
                ? "Comparing " + shortSha(diff.before_sha()) + " → " + shortSha(diff.after_sha())
                : "Comparing " + shortSha(diff.before_sha()) + " → working tree (in progress) - updates automatically";
        return note(text);
    }

    private static String shortSha(final String sha) {
        return sha == null || sha.isBlank() ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    private static Component diffBlock(final String diffText) {
        final var block = new Div();
        block.getStyle()
                .set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace")
                .set("font-size", "12px")
                .set("background", "var(--lumo-contrast-10pct)")
                .set("padding", "12px")
                .set("border-radius", "6px")
                .set("overflow-x", "auto")
                .set("white-space", "pre")
                .set("margin", "6px 0");
        for (final String line : diffText.split("\n", -1)) {
            final var span = new Span(line.isEmpty() ? " " : line);
            span.getStyle().set("display", "block");
            if (line.startsWith("+++") || line.startsWith("---")) {
                span.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-weight", "600");
            } else if (line.startsWith("+")) {
                span.getStyle().set("color", "var(--lumo-success-text-color)");
            } else if (line.startsWith("-")) {
                span.getStyle().set("color", "var(--lumo-error-text-color)");
            } else if (line.startsWith("@@")) {
                span.getStyle().set("color", "var(--lumo-primary-text-color)").set("font-weight", "600");
            }
            block.add(span);
        }
        return block;
    }

    private static Span note(final String text) {
        final var span = new Span(text);
        span.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.85em")
                .set("display", "block").set("margin", "2px 0 8px 0");
        return span;
    }
}
