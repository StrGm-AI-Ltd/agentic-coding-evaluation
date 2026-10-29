package com.strgmai.ace.ui;

import com.vaadin.flow.component.html.Div;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

/** #575, found live: a Plan grid row click can race the grid's own 2s poll refresh
 *  (planGrid.setItems(planTasks) replaces the whole data provider wholesale) - the click's own
 *  item-key resolution can land on no item at all, handing open() a null task. This used to reach
 *  task.id() on the very next line as an unguarded NullPointerException, surfaced to
 *  DefaultErrorHandler - the click target evaporated under a concurrent refresh, not a real
 *  operator-facing error. */
class JobTaskDialogTest {

    @Test
    void openIsANoOpWhenTheClickedTaskIsNull() {
        // must not throw - that is the entire regression this guards against. A null task returns
        // before any Dialog/component construction, so no live UI/session is needed here.
        JobTaskDialog.open(mock(ServiceClient.class), "job-1", null, () -> null, mock(JobLiveState.class), new Div(), () -> {});
    }
}
