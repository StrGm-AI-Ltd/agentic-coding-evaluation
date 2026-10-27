package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.RouterLink;

import java.util.HashMap;
import java.util.Map;

/** App chrome: brand + horizontal nav, mirroring the Jinja2 top bar - plus (#474/#485) the
 *  operator-resizable split between a tab's own content and its "Full manual" wiki pane.
 *
 *  AppLayout's own showRouterLayoutContent(HasElement) - decompiled and confirmed directly, no
 *  public doc covers this - just calls setContent((Component) content), replacing whatever
 *  AppLayout.content held. Calling setContent(split) once here, then overriding
 *  showRouterLayoutContent to redirect into contentSlot instead of ever calling super, keeps the
 *  split itself as AppLayout's one permanent content component while each navigated view swaps in
 *  and out underneath it - the same "childHolder" pattern Vaadin's own RouterLayout docs show for
 *  a plain Div, just inside AppLayout's slot instead. */
public class MainLayout extends AppLayout implements BeforeEnterObserver {

    private final Map<Class<?>, Tab> tabsByView = new HashMap<>();
    private final Tabs tabs = new Tabs();
    private final SplitLayout split = new SplitLayout();
    private final Div contentSlot = new Div();
    private final VerticalLayout manualSlot = new VerticalLayout();
    private boolean manualOpen;

    public MainLayout() {
        setPrimarySection(Section.NAVBAR);

        final var brand = new Span("ACE");
        brand.getStyle().set("font-weight", "700").set("font-size", "large")
                .set("margin-left", "24px").set("margin-right", "24px");

        tab("Info", InfoView.class);
        tab("Runs", RunsView.class, RunDetailView.class);
        tab("Groups", GroupsView.class);
        tab("Compare", CompareView.class);
        tab("Queue", JobsView.class, JobDetailView.class, JobNewView.class);
        tab("Experiments", ExperimentsView.class, ExperimentDetailView.class, ExperimentNewView.class);
        tab("Preflight", PreflightView.class);
        tab("LLM Providers", LlmProvidersView.class);

        addToNavbar(brand, tabs);

        // #486, found live: a plain Div with setSizeFull() has no scroll mechanism of its own -
        // its default overflow is "clip", not "auto" - so a routed view taller than the fixed
        // height split/contentSlot now impose (previously AppLayout's own content slot grew with
        // its content; this wrapper doesn't) left everything past the first screenful completely
        // unreachable, no scrollbar anywhere. contentSlot is now the one scroll container for
        // every tab's own main content.
        contentSlot.setSizeFull();
        contentSlot.getStyle().set("overflow-y", "auto");
        manualSlot.setSizeFull();
        manualSlot.setPadding(true);
        manualSlot.setSpacing(false);
        split.setSizeFull();
        split.addToPrimary(contentSlot);
        setContent(split);
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

    /** Overrides AppLayout's own default (setContent(routedView), replacing split wholesale) -
     *  see class javadoc. Every navigation closes a manual pane left open by the PREVIOUS tab:
     *  it's that tab's own wiki page, not the one just navigated to. */
    @Override
    public void showRouterLayoutContent(final HasElement content) {
        contentSlot.getElement().removeAllChildren();
        if (content != null) {
            contentSlot.getElement().appendChild(content.getElement());
        }
        closeManual();
    }

    /** #485: called from TabManual's "Full manual" button. Reveals manualSlot as the split's
     *  secondary pane (first click only - a second click on a DIFFERENT tab's banner just
     *  re-renders the existing, already-open pane with that tab's own page) with a draggable
     *  divider the operator can drag to resize either side - setSplitterPosition is only the
     *  INITIAL width, never re-imposed afterward. */
    void openManual(final String wikiPage) {
        if (!manualOpen) {
            split.addToSecondary(manualSlot);
            split.setSplitterPosition(65);
            manualOpen = true;
        }
        TabManual.renderInto(manualSlot, wikiPage, this::closeManual);
    }

    void closeManual() {
        if (manualOpen) {
            split.remove(manualSlot);
            manualOpen = false;
        }
    }
}
