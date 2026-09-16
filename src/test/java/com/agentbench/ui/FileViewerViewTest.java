package com.agentbench.ui;

import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The new-tab file viewer: JSON gets pretty-printed, everything else stays verbatim. */
class FileViewerViewTest {

    @Test
    void format_prettyPrintsCompactJson() {
        String pretty = FileViewerView.format("{\"a\":1,\"b\":{\"c\":[1,2]}}");
        assertTrue(pretty.contains("\n  "), "pretty-printed with indentation");
        assertTrue(pretty.contains("\"a\" : 1"));
        // formatting must not change the value, only the layout
        assertTrue(Json.MAPPER.readTree(pretty).equals(
                Json.MAPPER.readTree("{\"a\":1,\"b\":{\"c\":[1,2]}}")));
    }

    @Test
    void format_leavesNonJsonVerbatim() {
        assertEquals("plain log line\nsecond line", FileViewerView.format("plain log line\nsecond line"));
        assertEquals("", FileViewerView.format(""));
    }

    @Test
    void format_jsonlStaysVerbatim() {
        String jsonl = "{\"a\":1}\n{\"a\":2}\n";
        assertEquals(jsonl, FileViewerView.format(jsonl),
                "multi-record JSONL does not parse as one value, so it renders as-is");
    }

    @Test
    void queryParam_firstValueOrNull() {
        Location location = new Location("file-view",
                QueryParameters.simple(Map.of("run", "he-1", "path", "packs/T1.json")));
        assertEquals("he-1", FileViewerView.queryParam(location, "run"));
        assertEquals("packs/T1.json", FileViewerView.queryParam(location, "path"));
        assertNull(FileViewerView.queryParam(location, "missing"));
    }
}
