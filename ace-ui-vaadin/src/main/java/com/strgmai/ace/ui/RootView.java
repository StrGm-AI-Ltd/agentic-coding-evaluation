package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;

/** #473: "/" redirects to Info - the project's goal and a user-manual landing page, the first
 *  thing an operator (or a hackathon judge) should see, not a bare runs list. */
@Route(value = "", layout = MainLayout.class)
public class RootView extends Div implements BeforeEnterObserver {

    @Override
    public void beforeEnter(final BeforeEnterEvent event) {
        event.forwardTo("info");
    }
}
