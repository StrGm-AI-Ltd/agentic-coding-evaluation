package com.strgmai.ace.ui;

import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;

/** Every routed view's own page-title row: a plain (non-"permanent") Drawer Toggle next to the
 *  page's H2 heading, following the ".aura-view-header" convention from the official Aura
 *  example (vaadin/web-components:dev/aura/aura-view.css - a dev-only companion stylesheet, not
 *  part of the published Aura.STYLESHEET package; the matching rules are ported into ACE's own
 *  styles.css). Aura's own CSS there keeps this toggle hidden whenever the drawer's own
 *  "permanent" toggle (see MainLayout's brand row) is already visible, only surfacing it once
 *  there'd otherwise be no way to reopen a closed drawer. */
public class ViewHeader extends HorizontalLayout {
    private final H2 heading = new H2();

    public ViewHeader(final String title) {
        addClassName("aura-view-header");
        setSpacing(false);
        setWidthFull();
        final var toggle = new DrawerToggle();
        toggle.addThemeVariants(ButtonVariant.TERTIARY);
        heading.setText(title);
        add(toggle, heading);
    }

    /** For the detail views (Job/Run/Experiment), whose title is only known after a successful
     *  load and carries its own larger font-size override. */
    public H2 getHeading() {
        return heading;
    }
}
