package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesBrowserTest {

    @TempDir
    Path dir;

    private void write(Path path, String content) throws IOException {
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

        List<String> files = FilesBrowser.listFiles(dir.toString());
        assertEquals(List.of("alpha.log", "instructions/T1.json", "instructions/T2.json", "oracle.json"), files);
    }

    @Test
    void excludesWorkspaceAndNonRegularEntries() throws IOException {
        write(dir.resolve("workspace/secret.json"), "{}");
        write(dir.resolve("sub/ok.txt"), "x");
        Files.createDirectory(dir.resolve("emptydir"));

        List<String> files = FilesBrowser.listFiles(dir.toString());
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
        for (String text : new String[]{".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml"}) {
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
}
