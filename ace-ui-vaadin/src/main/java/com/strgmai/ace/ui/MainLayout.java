package com.strgmai.ace.ui;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.RouterLink;

import java.util.HashMap;
import java.util.Map;

/** App chrome: brand + horizontal nav, mirroring the Jinja2 top bar. */
public class MainLayout extends AppLayout implements BeforeEnterObserver {

    private final Map<Class<?>, Tab> tabsByView = new HashMap<>();
    private final Tabs tabs = new Tabs();

    public MainLayout() {
        setPrimarySection(Section.NAVBAR);

        final var brand = new Span("ACE");
        brand.getStyle().set("font-weight", "700").set("font-size", "large").set("margin-right", "24px");

        tab("Runs", RunsView.class, RunDetailView.class);
        tab("Groups", GroupsView.class);
        tab("Compare", CompareView.class);
        tab("Queue", JobsView.class, JobDetailView.class, JobNewView.class);
        tab("Experiments", ExperimentsView.class, ExperimentDetailView.class, ExperimentNewView.class);
        tab("Preflight", PreflightView.class);

        addToNavbar(brand, tabs);
    }

    private void tab(final String label, final Class<? extends Component> primary, final Class<?>... alsoMapped) {
        final var tab = new Tab(new RouterLink(label, primary));
        tabs.add(tab);
        tabsByView.put(primary, tab);
        for (final var view : alsoMapped) {
            tabsByView.put(view, tab);
        }
    }

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        final var tab = tabsByView.get(event.getNavigationTarget());
        if (tab != null) {
            tabs.setSelectedTab(tab);
        }
    }
}
