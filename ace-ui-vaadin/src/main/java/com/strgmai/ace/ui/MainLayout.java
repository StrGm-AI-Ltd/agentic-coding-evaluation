package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.HasElement;
import com.vaadin.flow.component.UI;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** App chrome: brand + left sidebar nav - mirrors the Aura "Night operator" reference's own
 *  layout (#476 PR 2/2; PR 1 shipped the dark palette/typography this sidebar now wears). The
 *  drawer replaces the old top AppLayout/Tabs navbar: a standalone "Overview" item, then two
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
 *  #476 PR 3/3: the Queue/LLM Providers nav badges are this layout's first-ever ServiceClient
 *  dependency - refreshed on a background thread (NOT
 *  UI.setPollInterval(), which JobDetailView already owns for its own 2s live poll and which is
 *  one global value per UI, not layered) that calls ui.access() the same way JobEventLoop already
 *  does for job-page SSE, piggybacking on the app's existing @Push(AUTOMATIC) rather than adding a
 *  second, competing poll loop.
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
    private static final Logger log = LoggerFactory.getLogger(MainLayout.class);

    private final ServiceClient client;
    private final SplitLayout split = new SplitLayout();
    private final Div contentSlot = new Div();
    private final VerticalLayout manualSlot = new VerticalLayout();
    private boolean manualOpen;

    private final SideNavItem queueNavItem;
    private final SideNavItem providersNavItem;
    private final Span footerStatusDot = new Span();
    private transient Thread chromeRefreshThread;
    private volatile boolean chromeRefreshStopped;

    public MainLayout(final ServiceClient client) {
        this.client = client;
        setPrimarySection(Section.DRAWER);
        // the drawer auto-collapses to a hamburger-toggled overlay on narrow viewports (AppLayout's
        // own built-in responsive behavior) - this is what reopens it there; always visible but
        // inert (nothing to toggle) once the drawer is pinned open on a wide screen
        addToNavbar(new DrawerToggle());

        // #529: Aura migration - #476/#527's hand-built brand-row CSS (the divider, the gradient
        // logo-mark box sized/colored to match the mockup's own .brand-mark) removed outright
        // rather than retargeted to Aura tokens, per the operator's own explicit call: don't
        // recreate the Lumo-era custom look under Aura, rely on Aura's own standard appearance.
        // Just the wordmark text now, no separate logo box.
        final var brand = new HorizontalLayout();
        brand.setAlignItems(FlexComponent.Alignment.CENTER);
        brand.setPadding(true);
        final var brandName = new Span("ACE");
        brandName.getStyle().set("font-weight", "700").set("font-size", "1.1em").set("letter-spacing", "0.02em");
        brand.add(brandName);

        // standalone, ungrouped - mirrors the reference's own "Overview" sitting outside any
        // collapsible section (same label now too, not just the same position). A bare
        // `new SideNav()` (no label) renders with no group header.
        final var overview = new SideNav();
        overview.addItem(navItem("Overview", InfoView.class, VaadinIcon.INFO_CIRCLE_O));

        // #476 PR 3/3: these two carry a live badge (see refreshLiveChrome()), so they're kept as
        // their own locals/fields instead of being built inline in the addItem(...) call below.
        queueNavItem = navItem("Queue", JobsView.class, VaadinIcon.TASKS);
        providersNavItem = navItem("LLM Providers", LlmProvidersView.class, VaadinIcon.CONNECT);

        final var benchmarking = new SideNav("Benchmarking");
        benchmarking.setCollapsible(true);
        benchmarking.addItem(
                navItem("Runs", RunsView.class, VaadinIcon.PLAY),
                navItem("Groups", GroupsView.class, VaadinIcon.GRID_SMALL),
                navItem("Compare", CompareView.class, VaadinIcon.SPLIT),
                queueNavItem,
                navItem("Experiments", ExperimentsView.class, VaadinIcon.FLASK));

        final var system = new SideNav("System");
        system.setCollapsible(true);
        system.addItem(
                navItem("Preflight", PreflightView.class, VaadinIcon.CHECK_CIRCLE),
                providersNavItem);

        final var menu = new VerticalLayout(overview, benchmarking, system);
        menu.setPadding(false);
        menu.setSpacing(false);

        final var scroller = new Scroller(menu);
        scroller.addThemeVariants(ScrollerVariant.OVERFLOW_INDICATORS);
        scroller.setSizeFull();

        // #476 PR 3/3: the local/oMLX backend's own connection status - a plain status dot kept
        // at the bottom of the drawer (scroller above flex-grows via setSizeFull(), leaving this
        // at its natural size pinned below it), updated by refreshLiveChrome().
        // #529: the status-dependent background coloring (gray/green/red, set in
        // refreshLiveChrome() below) removed outright, same as the rest of this migration's
        // custom-color strip - the dot keeps its shape, just no longer color-codes live status.
        footerStatusDot.getStyle().set("display", "inline-block").set("width", "8px").set("height", "8px")
                .set("border-radius", "50%").set("margin-right", "6px");
        final var footerStatusText = new Span("ace.strgm-ai.com");
        footerStatusText.getStyle().set("font-size", "0.8em");
        final var footer = new HorizontalLayout(footerStatusDot, footerStatusText);
        footer.setPadding(true);
        footer.setSpacing(false);
        footer.setAlignItems(FlexComponent.Alignment.CENTER);

        addToDrawer(brand, scroller, footer);

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

        // #476 PR 3/3: MainLayout is one instance per UI, reused across every navigation (that's
        // the whole point of split/contentSlot above) - so its own attach/detach fire once each,
        // same lifecycle JobDetailView's SSE thread already uses for ensureSse()/stopSse().
        addAttachListener(event -> {
            refreshLiveChrome();   // first paint now, instead of waiting for the first tick below
            ensureChromeRefresh(event.getUI());
        });
        addDetachListener(event -> stopChromeRefresh());
    }

    /** Every 20s, for as long as this layout's UI stays attached (the whole browser tab's
     *  lifetime) - cheap relative to that interval, and no page's own more frequent polling
     *  (JobDetailView's 2s) shares this thread or UI.setPollInterval() with it. */
    private void ensureChromeRefresh(final UI ui) {
        if (chromeRefreshThread != null && chromeRefreshThread.isAlive()) {
            return;
        }
        chromeRefreshStopped = false;
        chromeRefreshThread = new Thread(() -> {
            while (!chromeRefreshStopped) {
                try {
                    Thread.sleep(20_000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (chromeRefreshStopped) {
                    return;
                }
                ui.access(this::refreshLiveChrome);
            }
        }, "main-layout-chrome-refresh");
        chromeRefreshThread.setDaemon(true);
        chromeRefreshThread.start();
    }

    private void stopChromeRefresh() {
        chromeRefreshStopped = true;
    }

    /** The Queue/LLM Providers nav badges and the footer's connection-status dot - each wrapped
     *  in its own try/catch so one failing call (e.g. the service briefly unreachable) never blanks
     *  out a badge that was showing a real count a moment ago, and never stops the other refresh. */
    private void refreshLiveChrome() {
        try {
            final var jobs = client.jobs();
            final long active = jobs.stream().filter(j -> !JobStatuses.isTerminal(j.status())).count();
            queueNavItem.setSuffixComponent(active == 0 ? null : Badges.text(String.valueOf(active), Badges.PRIMARY));
        } catch (final Exception e) {
            log.debug("could not refresh the Queue nav badge: {}", e.toString());
        }

        try {
            final var providers = client.providers();
            // the local/oMLX row's own missing key is not a misconfiguration (#65: it may need none at
            // all) - only a REMOTE provider missing one is actually something an operator must fix.
            final long misconfigured = providers.stream().filter(p -> !p.has_api_key() && !p.is_local()).count();
            providersNavItem.setSuffixComponent(misconfigured == 0 ? null : Badges.text(String.valueOf(misconfigured), Badges.WARNING));
        } catch (final Exception e) {
            log.debug("could not refresh the LLM Providers nav badge: {}", e.toString());
        }
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
