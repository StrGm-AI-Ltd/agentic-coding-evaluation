package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** #270: the actual file LISTING (top-level + one subdir level, workspace/ excluded, size stats)
 *  moved to BenchController.files() (see BenchControllerTest's own equivalent of this file's
 *  former listFiles/sizeOf/sizeOr tests) - FilesBrowser itself now only renders whatever
 *  ServiceClient.runFiles() returns, so what's left here is purely the client-side-only pieces:
 *  the size column's own formatting, text-vs-raw routing, and URL encoding. */
class FilesBrowserTest {

    /** The size column's display (T-9a) - now formatting a size the SERVER already computed,
     *  rather than stat'ing a local file. */
    @Test
    void sizeText_formatsBytesKiBMiB() {
        assertEquals("512 B", FilesBrowser.sizeText(512));
        assertEquals("2.0 KiB", FilesBrowser.sizeText(2048));
        assertEquals("3.0 MiB", FilesBrowser.sizeText(3L * 1024 * 1024));
        assertEquals("–", FilesBrowser.sizeText(-1), "BenchController.fileEntry()'s own failed-stat marker (-1) renders as –");
    }

    @Test
    void isText_suffixMatrix() {
        for (final var text : new String[]{".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml"}) {
            assertTrue(FilesBrowser.isText("f" + text), text + " is viewable text");
        }
        assertFalse(FilesBrowser.isText("f.png"));
        assertFalse(FilesBrowser.isText("noext"));
        assertTrue(FilesBrowser.isText("dir/f.json"));
    }

    /** The new-tab "view" link: segment-encoded run and path, slashes preserved in the path. */
    @Test
    void viewRoute_encodesRunAndPathSegments() {
        assertEquals("file-view?run=r1&path=packs/T1.json",
                FilesBrowser.viewRoute("r1", "packs/T1.json"));
        assertEquals("file-view?run=r1&path=a%20b.txt",
                FilesBrowser.viewRoute("r1", "a b.txt"), "spaces become %20");
        assertEquals("file-view?run=r1&path=100%25.json",
                FilesBrowser.viewRoute("r1", "100%.json"), "percent is escaped");
        assertEquals("file-view?run=he-1&path=t%C3%A9.json",
                FilesBrowser.viewRoute("he-1", "té.json"), "non-ASCII is escaped");
    }

    /** #39: clicking a row opens the file directly - text files at the formatted viewer, everything
     *  else straight to the service's raw file endpoint. */
    @Test
    void fileUrl_routesTextToTheViewerAndEverythingElseToTheRawEndpoint() {
        final var client = mock(ServiceClient.class);
        when(client.baseUrl()).thenReturn("http://svc:8080");

        assertEquals("file-view?run=r1&path=oracle.json", FilesBrowser.fileUrl(client, "r1", "oracle.json"));
        assertEquals(Links.rawFileUrl("http://svc:8080", "r1", "screenshot.png"),
                FilesBrowser.fileUrl(client, "r1", "screenshot.png"));
    }
}
