package com.agentbench.ui;

import com.vaadin.flow.component.badge.Badge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadgesTest {

    @Test
    void theme_mapsServiceStatuses() {
        assertEquals(Badges.SUCCESS, Badges.theme("succeeded"));
        assertEquals(Badges.SUCCESS, Badges.theme("pass"));
        assertEquals(Badges.SUCCESS, Badges.theme("finished"));
        assertEquals(Badges.SUCCESS, Badges.theme("true"));
        assertEquals(Badges.ERROR, Badges.theme("failed"));
        assertEquals(Badges.ERROR, Badges.theme("fail"));
        assertEquals(Badges.ERROR, Badges.theme("error"));
        assertEquals(Badges.ERROR, Badges.theme("false"));
        assertEquals(Badges.WARNING, Badges.theme("blocked"));
        assertEquals(Badges.WARNING, Badges.theme("waiting_lock"));
        assertEquals(Badges.WARNING, Badges.theme("skipped"));
        assertEquals(Badges.WARNING, Badges.theme("infra"));
        assertEquals(Badges.PRIMARY, Badges.theme("running"));
        assertEquals(Badges.CONTRAST, Badges.theme("queued"));
        assertEquals(Badges.CONTRAST, Badges.theme("cancelled"));
        assertEquals(Badges.CONTRAST, Badges.theme("not_attempted"));
        assertEquals(Badges.CONTRAST, Badges.theme(null));
        assertEquals(Badges.CONTRAST, Badges.theme(""));
        assertEquals(Badges.CONTRAST, Badges.theme("whatever-else"));
    }

    @Test
    void status_nullRendersQuestionMark() {
        Badge badge = Badges.status(null);
        assertEquals("?", badge.getElement().getText());
    }

    @Test
    void textCarriesTheme() {
        Badge badge = Badges.text("INVALID", Badges.ERROR);
        assertTrue(badge.getElement().getAttribute("theme").contains("error"));
    }
}
