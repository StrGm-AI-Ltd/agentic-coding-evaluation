package com.strgmai.ace.ui;

import com.vaadin.flow.component.badge.Badge;

/**
 * Status → colored badge, mirroring the .badge/.status CSS of the original UI.
 *
 * <p>Two intentional divergences from the service's own colors, both upgrades:
 * {@code blocked} is amber here (the service paints it red) because a blocked job is
 * an actionable recovery state in this UI (Requeue), not a failure; and
 * {@code not_attempted} is neutral (the service: red) because the oracle scores it
 * as a fail already — see README "What it measures".
 */
public final class Badges {

    public static final String SUCCESS = "success";
    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String PRIMARY = "primary";
    public static final String CONTRAST = "contrast";

    private Badges() {
    }

    public static Badge status(final String status) {
        final var badge = new Badge(status == null ? "?" : status);
        badge.setThemeName(theme(status));
        return badge;
    }

    public static Badge text(final String label, final String theme) {
        final var badge = new Badge(label);
        badge.setThemeName(theme);
        return badge;
    }

    public static String theme(final String status) {
        final var s = status == null ? "" : status.toLowerCase();
        return switch (s) {
            case "pass", "succeeded", "finished", "true" -> SUCCESS;
            case "fail", "failed", "error", "false" -> ERROR;
            case "blocked", "waiting_lock", "skipped", "infra" -> WARNING;
            case "running" -> PRIMARY;
            default -> CONTRAST; // queued, cancelled, not_attempted, …
        };
    }
}
