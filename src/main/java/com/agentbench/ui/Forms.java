package com.agentbench.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Shared form-section helpers for the two creation forms (C-1). */
public final class Forms {

    private Forms() {
    }

    /** A titled, vertically stacked section of wrapped field rows. */
    public static VerticalLayout section(String title, HorizontalLayout... rows) {
        H4 header = new H4(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        VerticalLayout sectionLayout = new VerticalLayout(header);
        sectionLayout.setPadding(false);
        sectionLayout.setSpacing(false);
        for (HorizontalLayout row : rows) {
            sectionLayout.add(row);
        }
        return sectionLayout;
    }

    /** A horizontally arranged, wrapping row of fields, aligned by their baselines. */
    public static HorizontalLayout row(Component... fields) {
        HorizontalLayout layout = new HorizontalLayout(fields);
        layout.getStyle().set("flex-wrap", "wrap");
        layout.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.END);
        layout.setSpacing(true);
        return layout;
    }
}
