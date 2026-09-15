package com.agentbench.ui;

import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

import java.util.List;

/** Leaderboard — the UI twin of GET /api/groups: ranked (k ≥ 5) and indicative groups. */
@Route(value = "groups", layout = MainLayout.class)
public class GroupsView extends VerticalLayout {

    private final transient ServiceClient client;

    public GroupsView(ServiceClient client) {
        this.client = client;
        setPadding(true);
        render();
    }

    private void render() {
        removeAll();
        add(new H2("Groups — pooled by task · model · key"));
        add(new Span("A leaderboard entry needs k ≥ 5 comparable valid runs; smaller groups are indicative and never ranked."));

        Api.GroupResponse groups;
        try {
            groups = client.groups();
        } catch (Exception e) {
            add(Panels.error(client.errorText(e)));
            return;
        }

        List<Api.Group> ranked = groups == null || groups.ranked() == null ? List.of() : groups.ranked();
        List<Api.Group> indicative = groups == null || groups.indicative() == null ? List.of() : groups.indicative();

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
            for (Api.Group group : indicative) {
                add(new GroupCard(group, 0));
            }
        }
    }
}
