package com.strgmai.ace.ui;

import com.vaadin.flow.component.grid.Grid;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** #9: the grid navigates on any row click, but gave no visual hint that a row was clickable -
 *  it must carry the "clickable-rows" class name (see clickable-grid.css for the hover styling). */
class ExperimentsViewTest {

    @Test
    void gridCarriesTheClickableRowsClassName() {
        final var view = new ExperimentsView(mock(ServiceClient.class));

        final Grid<?> grid = view.getChildren()
                .filter(Grid.class::isInstance)
                .map(Grid.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ExperimentsView has no Grid child"));

        assertTrue(grid.getClassNames().contains("clickable-rows"));
    }
}
