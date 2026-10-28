package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.dependency.CssImport;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.orderedlayout.ScrollerVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.component.splitlayout.SplitLayout;

/** App chrome: brand + left sidebar nav - mirrors the Aura "Night operator" reference's own
 *  layout (#476 PR 2/2; PR 1 shipped the dark palette/typography this sidebar now wears). The
 *  drawer replaces the old top AppLayout/Tabs navbar: a standalone "Info" item, then two
 *  collapsible SideNav groups (Benchmarking, System) matching the reference's own grouped-section
 *  pattern, applied to ACE's real 8 views rather than the reference's literal SaaS labels.
 *  SideNavItem's own setMatchNested(true) replaces the old hand-rolled tabsByView/beforeEnter()
 *  highlighting - Vaadin's router already knows "runs/:runId" nests under "runs", "jobs/:jobId"
 *  and "jobs/new" both nest under "jobs", etc. (confirmed against every view's own @Route), so
 *  there's no longer a manual "alsoMapped" list to keep in sync by hand.
 *
 *  Plus (#474/#485) the operator-resizable split between a tab's own content and its "Full
 *  manual" wiki pane.
 *
 *  AppLayout's own showRouterLayoutContent(HasElement) - decompiled and confirmed directly, no
 *  public doc covers this - just calls setContent((Component) content), replacing whatever
 *  AppLayout.content held. Calling setContent(split) once here, then overriding
 *  showRouterLayoutContent to redirect into contentSlot instead of ever calling super, keeps the
 *  split itself as AppLayout's one permanent content component while each navigated view swaps in
 *  and out underneath it - the same "childHolder" pattern Vaadin's own RouterLayout docs show for
 *  a plain Div, just inside AppLayout's slot instead.
 *
 *  Found live (deployed only, never reproduced in dev mode): manual-pane images rendered at their
 *  full natural width instead of scaling to the pane, overflowing with a horizontal scrollbar -
 *  TabManual's own class javadoc assumed FileViewerView's @CssImport("./styles/md-viewer.css")
 *  styles ".md-body" app-wide "since Flow's frontend bundle is app-wide, not per-route" - true only
 *  in DEV mode, which eagerly scans and injects every @CssImport found anywhere on the classpath
 *  regardless of which routes were ever visited. A PRODUCTION build code-splits per component
 *  instead: that chunk, and so its CSS, only loads once FileViewerView's own standalone
 *  (layout-less) /file-view route is actually opened - confirmed directly against the deployed
 *  jar's own bundle (the compiled ".md-body img{max-width:100%}" rule IS present in it) and the
 *  live DOM (no matching style tag or adoptedStyleSheet - never injected). Repeating the
 *  SAME import here - MainLayout is instantiated for every route under the app chrome, including
 *  every TabManual-opened manual pane - is the fix: duplicate annotations on the same frontend
 *  file across classes are a normal, deduped-by-Vite Vaadin pattern, not a real duplicate. */
@CssImport("./styles/md-viewer.css")
public class MainLayout extends AppLayout {

    private final SplitLayout split = new SplitLayout();
    private final Div contentSlot = new Div();
    private final VerticalLayout manualSlot = new VerticalLayout();
    private boolean manualOpen;

    public MainLayout() {
        setPrimarySection(Section.DRAWER);
        // the drawer auto-collapses to a hamburger-toggled overlay on narrow viewports (AppLayout's
        // own built-in responsive behavior) - this is what reopens it there; always visible but
        // inert (nothing to toggle) once the drawer is pinned open on a wide screen
        addToNavbar(new DrawerToggle());

        final var brand = new HorizontalLayout();
        brand.setAlignItems(FlexComponent.Alignment.CENTER);
        brand.setPadding(true);
        final var brandName = new Span("ACE");
        brandName.getStyle().set("font-weight", "700").set("font-size", "1.1em").set("letter-spacing", "0.02em");
        brand.add(brandName);

        // standalone, ungrouped - mirrors the reference's own "Overview" sitting outside any
        // collapsible section. A bare `new SideNav()` (no label) renders with no group header.
        final var info = new SideNav();
        info.addItem(navItem("Info", InfoView.class, VaadinIcon.INFO_CIRCLE_O));

        final var benchmarking = new SideNav("Benchmarking");
        benchmarking.setCollapsible(true);
        benchmarking.addItem(
                navItem("Runs", RunsView.class, VaadinIcon.PLAY),
                navItem("Groups", GroupsView.class, VaadinIcon.GRID_SMALL),
                navItem("Compare", CompareView.class, VaadinIcon.SPLIT),
                navItem("Queue", JobsView.class, VaadinIcon.TASKS),
                navItem("Experiments", ExperimentsView.class, VaadinIcon.FLASK));

        final var system = new SideNav("System");
        system.setCollapsible(true);
        system.addItem(
                navItem("Preflight", PreflightView.class, VaadinIcon.CHECK_CIRCLE),
                navItem("LLM Providers", LlmProvidersView.class, VaadinIcon.CONNECT));

        final var menu = new VerticalLayout(info, benchmarking, system);
        menu.setPadding(false);
        menu.setSpacing(false);

        final var scroller = new Scroller(menu);
        scroller.addThemeVariants(ScrollerVariant.OVERFLOW_INDICATORS);
        scroller.setSizeFull();

        addToDrawer(brand, scroller);

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

    private static SideNavItem navItem(final String label, final Class<? extends Component> view, final VaadinIcon icon) {
        final var item = new SideNavItem(label, view, new Icon(icon));
        item.setMatchNested(true);
        return item;
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
