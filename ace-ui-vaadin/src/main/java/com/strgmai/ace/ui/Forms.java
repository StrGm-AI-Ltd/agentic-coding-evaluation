package com.strgmai.ace.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

/** Shared form-section helpers for the two creation forms (C-1). */
public final class Forms {

    private Forms() {
    }

    /** A titled, always-visible section whose rows sit in a responsive multi-column grid. */
    public static VerticalLayout section(final String title, final Component[]... rows) {
        final var header = new H4(title);
        header.getStyle().set("margin", "16px 0 4px 0");
        final var sectionLayout = new VerticalLayout(header, grid(rows));
        sectionLayout.setPadding(false);
        sectionLayout.setSpacing(false);
        return sectionLayout;
    }

    /** A titled section collapsed by default - progressive disclosure for advanced/rarely-touched fields. */
    public static Details collapsibleSection(final String title, final Component[]... rows) {
        final var details = new Details(title, grid(rows));
        details.setOpened(false);
        return details;
    }

    /** A row of fields, grouped side by side within the enclosing section's grid. */
    public static Component[] row(final Component... fields) {
        return fields;
    }

    private static FormLayout grid(final Component[]... rows) {
        final var form = new FormLayout();
        form.setAutoResponsive(true);
        for (final var row : rows) {
            final var formRow = form.addFormRow(row);
            for (final var field : row) {
                // both bundle more than one labeled sub-field into a single Component - a single
                // grid column is too narrow for either and squeezes/overlaps their neighbors
                if (field instanceof ProviderModelPicker) {
                    formRow.setColspan(field, 2);
                } else if (field instanceof CompactionStrategyList) {
                    formRow.setColspan(field, 100);   // always the full row width, any column count
                }
            }
        }
        return form;
    }
}
