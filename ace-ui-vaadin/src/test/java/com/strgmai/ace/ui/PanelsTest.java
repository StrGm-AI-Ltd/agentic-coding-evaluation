package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** #551: a tile's value (e.g. a long remote-provider model name) used to wrap across multiple
 *  lines, ballooning the tile's height past its row-mates - it should instead stay single-line
 *  with an ellipsis, and the full value should still be reachable via a tooltip. */
class PanelsTest {

    @Test
    void statTileTruncatesALongValueWithEllipsisAndExposesItAsATooltip() {
        final var longValue = "nebius/nvidia/NVIDIA-Nemotron-3-Nano-30B-A3B";
        final var tile = Panels.statTile("Model", longValue, null);
        final var valueSpan = tile.getChildren().toList().get(1);
        assertEquals("nowrap", valueSpan.getStyle().get("white-space"));
        assertEquals("ellipsis", valueSpan.getStyle().get("text-overflow"));
        assertEquals("hidden", valueSpan.getStyle().get("overflow"));
        assertEquals(longValue, valueSpan.getElement().getAttribute("title"));
    }
}
