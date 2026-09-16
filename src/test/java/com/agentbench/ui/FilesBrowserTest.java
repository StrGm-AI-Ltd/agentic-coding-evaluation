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

    @Test
    void isText_suffixMatrix() {
        for (String text : new String[]{".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml"}) {
            assertTrue(FilesBrowser.isText("f" + text), text + " is viewable text");
        }
        assertFalse(FilesBrowser.isText("f.png"));
        assertFalse(FilesBrowser.isText("noext"));
        assertTrue(FilesBrowser.isText("dir/f.json"));
    }

    @Test
    void truncateCapsOversizedContent() {
        String big = "x".repeat(FilesBrowser.MAX_DISPLAY_CHARS + 5);
        String truncated = FilesBrowser.truncateForDisplay(big);
        String expected = "x".repeat(FilesBrowser.MAX_DISPLAY_CHARS)
                + "\n\n… truncated at " + FilesBrowser.MAX_DISPLAY_CHARS
                + " chars — the full file is at the service link";
        assertEquals(expected, truncated);
    }

    @Test
    void smallContentUnchanged() {
        assertEquals("small", FilesBrowser.truncateForDisplay("small"));
    }
}
