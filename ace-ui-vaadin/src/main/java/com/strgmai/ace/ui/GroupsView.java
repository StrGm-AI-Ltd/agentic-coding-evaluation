package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/** Leaderboard — the UI twin of GET /api/groups: ranked (k ≥ 5) and indicative groups. */
@Route(value = "groups", layout = MainLayout.class)
public class GroupsView extends VerticalLayout {
    private static final Logger log = LoggerFactory.getLogger(GroupsView.class);

    private final ServiceClient client;

    public GroupsView(final ServiceClient client) {
        this.client = client;
        setPadding(true);
        // @Route views are cached per session — reload on every navigation, like ExperimentsView/JobsView
        addAttachListener(e -> render());
    }

    private void render() {
        removeAll();
        add(new H2("Groups — pooled by task · model · key"));
        add(new Span("A leaderboard entry needs k ≥ 5 comparable valid runs; smaller groups are indicative and never ranked."));

        final Api.GroupResponse groups;   // assigned exactly once below; a legal blank final
        try {
            groups = client.groups();
        } catch (final Exception e) {
            log.warn("could not load groups: {}", e.toString());
            add(Panels.error(client.errorText(e)));
            return;
        }

        // ternary combines List.of() (unconstrained) with List<Api.Group>: without a var's
        // target type this infers to something other than List<Api.Group> - keep explicit
        final List<Api.Group> ranked = groups == null || groups.ranked() == null ? List.of() : groups.ranked();
        final List<Api.Group> indicative = groups == null || groups.indicative() == null ? List.of() : groups.indicative();

        add(new H3("Ranked — k ≥ 5"));
        if (ranked.isEmpty()) {
            add(new Span("none yet"));
        } else {
            for (int i = 0; i < ranked.size(); i++) {
                add(new GroupCard(ranked.get(i), i + 1));
            }
        }

        add(new H3("Indicative — k < 5"));
        if (indicative.isEmpty()) {
            add(new Span("none"));
        } else {
            for (final var group : indicative) {
                add(new GroupCard(group, 0));
            }
        }
    }
}
