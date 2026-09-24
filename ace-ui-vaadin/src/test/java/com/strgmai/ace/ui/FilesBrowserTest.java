package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilesBrowserTest {

    @TempDir
    Path dir;

    private void write(final Path path, final String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void listsTopLevelSortedAndOneSubdirLevel() throws IOException {
        write(dir.resolve("oracle.json"), "{}");
        write(dir.resolve("alpha.log"), "x");
        write(dir.resolve("instructions/T1.json"), "{}");
        write(dir.resolve("instructions/T2.json"), "{}");
        write(dir.resolve("instructions/deep/T3.json"), "{}"); // two levels: must not appear

        final var files = FilesBrowser.listFiles(dir.toString());
        assertEquals(List.of("alpha.log", "instructions/T1.json", "instructions/T2.json", "oracle.json"), files);
    }

    @Test
    void excludesWorkspaceAndNonRegularEntries() throws IOException {
        write(dir.resolve("workspace/secret.json"), "{}");
        write(dir.resolve("sub/ok.txt"), "x");
        Files.createDirectory(dir.resolve("emptydir"));

        final var files = FilesBrowser.listFiles(dir.toString());
        assertEquals(List.of("sub/ok.txt"), files);
        assertFalse(files.stream().anyMatch(f -> f.startsWith("workspace/")));
    }

    @Test
    void blankMissingAndNullReturnEmpty() {
        assertEquals(List.of(), FilesBrowser.listFiles(null));
        assertEquals(List.of(), FilesBrowser.listFiles(""));
        assertEquals(List.of(), FilesBrowser.listFiles("/definitely/not/a/real/dir"));
    }

    /** The size column's display and sort key (T-9a). */
    @Test
    void sizeOf_formatsBytesKiBMiB() throws IOException {
        write(dir.resolve("a.json"), "x".repeat(512));
        write(dir.resolve("b.json"), "x".repeat(2048));
        write(dir.resolve("c.json"), "x".repeat(3 * 1024 * 1024));
        assertEquals("512 B", FilesBrowser.sizeOf(dir.toString(), "a.json"));
        assertEquals("2.0 KiB", FilesBrowser.sizeOf(dir.toString(), "b.json"));
        assertEquals("3.0 MiB", FilesBrowser.sizeOf(dir.toString(), "c.json"));
        assertEquals("–", FilesBrowser.sizeOf(dir.toString(), "missing.json"),
                "missing files format as – and sort last via sizeOr");
        assertEquals(Long.MAX_VALUE, FilesBrowser.sizeOr(dir.toString(), "missing.json"));
        assertEquals(2048L, FilesBrowser.sizeOr(dir.toString(), "b.json"));
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
