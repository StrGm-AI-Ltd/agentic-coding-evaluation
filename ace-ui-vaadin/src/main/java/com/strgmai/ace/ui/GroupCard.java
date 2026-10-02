package com.strgmai.ace.ui;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One leaderboard card — the Vaadin twin of the group_card macro in the Jinja2 UI:
 * k, mean ± 90 % CI, pass-k matrix chips, run links, collapsible stats.py output.
 */
public class GroupCard extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(GroupCard.class);

    public GroupCard(final ServiceClient client, final Api.Group group, final int rank) {
        setPadding(false);
        setSpacing(true);
        getStyle()
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-radius", "8px")
                .set("padding", "12px 16px")
                .set("margin", "6px 0");

        final var header = new H4(group.task() + " · " + group.mode() + " · " + group.model());
        header.getStyle().set("margin", "0");
        final var head = new HorizontalLayout(header);
        if (rank > 0) {
            head.add(Badges.text("#" + rank, Badges.PRIMARY));
        }
        final var key = new Span("key " + (group.key_hash() == null ? "" : group.key_hash().substring(0,
                Math.min(10, group.key_hash().length()))));
        key.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace").set("font-size", "12px");
        head.add(key);
        head.setAlignItems(Alignment.BASELINE);
        head.setSpacing(true);
        head.setPadding(false);
        add(head);

        if (group.refused() != null) {
            add(Panels.error("StatsService refused: " + group.refused()));
        } else {
            final var summary = group.summary();
            final var k = summary == null || summary.k() == null ? 0 : summary.k();
            final var kLine = new Span("k = " + k + " comparable of " + group.run_ids().size() + " runs");
            kLine.getStyle().set("font-weight", "600");
            add(kLine);
            if (k < 5) {
                add(Badges.text("indicative", Badges.CONTRAST));
            }
            if (k < group.run_ids().size()) {
                addExclusionBreakdown(client, group, k);
            }

            if (summary != null) {
                addStat("functional", summary.functional());
                addStat("composite", summary.composite());
                addStat("agent_result", summary.agent_result());

                if (summary.matrix() != null && !summary.matrix().isEmpty()) {
                    final var chips = new HorizontalLayout();
                    chips.setPadding(false);
                    chips.setSpacing(true);
                    chips.getStyle().set("flex-wrap", "wrap");
                    summary.matrix().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                final var stat = entry.getValue();
                                final var theme = Boolean.TRUE.equals(stat.pass_k()) ? Badges.SUCCESS
                                        : (stat.pass_rate() != null && stat.pass_rate() > 0) ? Badges.WARNING
                                        : Badges.ERROR;
                                final var chip = Badges.text(entry.getKey(), theme);
                                chip.getElement().setAttribute("title",
                                        entry.getKey() + ": pass rate " + stat.pass_rate());
                                chips.add(chip);
                            });
                    add(chips);
                }
            }
        }

        final var runs = new HorizontalLayout();
        runs.setPadding(false);
        runs.setSpacing(true);
        runs.getStyle().set("flex-wrap", "wrap");
        for (final var runId : group.run_ids()) {
            runs.add(Links.runLink(runId));
        }
        add(runs);

        final var printed = Panels.mono(group.printed());
        final var details = new Details("StatsService output", printed);
        add(details);
    }

    /** #194: k (comparable, valid runs) is always <= run_ids().size() (every poolable run in the
     *  group) - the gap between them was previously invisible, forcing a click into every run to
     *  find out why. Reconstructs the same two reasons StatsService.filterRuns() excludes on
     *  (invalid; partial - no weighted_score_pct, e.g. Docker-gated checks skipped) from fields
     *  Api.Run already exposes, via the one extra /api/runs call this needs - never a hard failure
     *  if that call fails, since this is purely informational. */
    private void addExclusionBreakdown(final ServiceClient client, final Api.Group group, final int k) {
        final List<Api.Run> runs;
        try {
            runs = client.runs(group.task(), group.model(), group.mode(), null, null);
        } catch (final Exception e) {
            log.debug("could not load runs for the exclusion breakdown of {}/{}: {}", group.task(), group.model(), e.toString());
            return;
        }
        final Set<String> runIds = new HashSet<>(group.run_ids());
        int invalid = 0, partial = 0;
        for (final var run : runs) {
            if (!runIds.contains(run.run_id())) continue;
            if (!Boolean.TRUE.equals(run.valid())) invalid++;
            else if (run.weighted_score_pct() == null) partial++;
        }
        if (invalid == 0 && partial == 0) return;   // the gap is real but not explained by either known reason
        final var parts = new ArrayList<String>();
        if (invalid > 0) parts.add(invalid + " invalid");
        if (partial > 0) parts.add(partial + " partial (docker skipped/infra)");
        final var excluded = new Span("excluded: " + String.join(", ", parts));
        excluded.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "13px");
        add(excluded);
    }

    private void addStat(final String name, final Api.Stats stats) {
        if (stats == null || stats.mean() == null) {
            return;
        }
        final var ci = stats.ci90() != null && stats.ci90().size() == 2
                ? "[" + Fmt.pct(stats.ci90().get(0)) + ", " + Fmt.pct(stats.ci90().get(1)) + "]"
                : "[–]";
        final var span = new Span(name + " mean " + Fmt.pct(stats.mean())
                + " (90% CI " + ci + (stats.n() != null ? " · n=" + stats.n() : "") + ")");
        span.getStyle().set("font-variant-numeric", "tabular-nums");
        add(span);
    }
}
