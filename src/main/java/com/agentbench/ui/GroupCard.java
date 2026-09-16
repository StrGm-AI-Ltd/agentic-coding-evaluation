package com.agentbench.ui;

import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.Component;

import java.util.Map;

/**
 * One leaderboard card — the Vaadin twin of the group_card macro in the Jinja2 UI:
 * k, mean ± 90 % CI, pass-k matrix chips, run links, collapsible stats.py output.
 */
public class GroupCard extends VerticalLayout {

    public GroupCard(Api.Group group, int rank) {
        setPadding(false);
        setSpacing(true);
        getStyle()
                .set("border", "1px solid var(--lumo-contrast-20%)")
                .set("border-radius", "8px")
                .set("padding", "12px 16px")
                .set("margin", "6px 0");

        H4 header = new H4(group.task() + " · " + group.mode() + " · " + group.model());
        header.getStyle().set("margin", "0");
        HorizontalLayout head = new HorizontalLayout(header);
        if (rank > 0) {
            head.add(Badges.text("#" + rank, Badges.PRIMARY));
        }
        Span key = new Span("key " + (group.key_hash() == null ? "" : group.key_hash().substring(0,
                Math.min(10, group.key_hash().length()))));
        key.getStyle().set("color", "var(--lumo-secondary-text-color)")
                .set("font-family", "ui-monospace, 'SF Mono', Menlo, monospace").set("font-size", "12px");
        head.add(key);
        head.setAlignItems(Alignment.BASELINE);
        head.setSpacing(true);
        head.setPadding(false);
        add(head);

        if (group.refused() != null) {
            add(Panels.error("stats.py refused: " + group.refused()));
        } else {
            Api.Summary summary = group.summary();
            int k = summary == null || summary.k() == null ? 0 : summary.k();
            Span kLine = new Span("k = " + k + " comparable of " + group.run_ids().size() + " runs");
            kLine.getStyle().set("font-weight", "600");
            add(kLine);
            if (k < 5) {
                add(Badges.text("indicative", Badges.CONTRAST));
            }

            if (summary != null) {
                addStat("functional", summary.functional());
                addStat("composite", summary.composite());
                addStat("agent_result", summary.agent_result());

                if (summary.matrix() != null && !summary.matrix().isEmpty()) {
                    HorizontalLayout chips = new HorizontalLayout();
                    chips.setPadding(false);
                    chips.setSpacing(true);
                    chips.getStyle().set("flex-wrap", "wrap");
                    summary.matrix().entrySet().stream()
                            .sorted(Map.Entry.comparingByKey())
                            .forEach(entry -> {
                                Api.CheckStat stat = entry.getValue();
                                String theme = Boolean.TRUE.equals(stat.pass_k()) ? Badges.SUCCESS
                                        : (stat.pass_rate() != null && stat.pass_rate() > 0) ? Badges.WARNING
                                        : Badges.ERROR;
                                Badge chip = Badges.text(entry.getKey(), theme);
                                chip.getElement().setAttribute("title",
                                        entry.getKey() + ": pass rate " + stat.pass_rate());
                                chips.add(chip);
                            });
                    add(chips);
                }
            }
        }

        HorizontalLayout runs = new HorizontalLayout();
        runs.setPadding(false);
        runs.setSpacing(true);
        runs.getStyle().set("flex-wrap", "wrap");
        for (String runId : group.run_ids()) {
            runs.add(Links.runLink(runId));
        }
        add(runs);

        Component printed = Panels.mono(group.printed());
        Details details = new Details("stats.py output", printed);
        add(details);
    }

    private void addStat(String name, Api.Stats stats) {
        if (stats == null || stats.mean() == null) {
            return;
        }
        String ci = stats.ci90() != null && stats.ci90().size() == 2
                ? "[" + Fmt.pct(stats.ci90().get(0)) + ", " + Fmt.pct(stats.ci90().get(1)) + "]"
                : "[–]";
        Span span = new Span(name + " mean " + Fmt.pct(stats.mean())
                + " (90% CI " + ci + (stats.n() != null ? " · n=" + stats.n() : "") + ")");
        span.getStyle().set("font-variant-numeric", "tabular-nums");
        add(span);
    }
}
