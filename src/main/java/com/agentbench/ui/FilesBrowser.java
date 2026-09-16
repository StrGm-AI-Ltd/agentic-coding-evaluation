package com.agentbench.ui;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Run file browser — the Vaadin twin of the Jinja2 run page's file section:
 * lists the run's results dir (top level + one subdir level, excluding workspace/,
 * mirroring run_files() in the service's api.py), and fetches text file content
 * through the service's /runs/{id}/files/{path}. Binary or oversized files link out.
 */
public class FilesBrowser extends VerticalLayout {

    static final int MAX_DISPLAY_CHARS = 400_000;

    private static final Set<String> TEXT_SUFFIXES =
            Set.of(".md", ".log", ".json", ".jsonl", ".txt", ".yaml", ".yml");

    private final ServiceClient client;
    private final String runId;
    private final Div content = new Div();

    public FilesBrowser(ServiceClient client, String runId, String resultsDir) {
        this.client = client;
        this.runId = runId;
        setPadding(false);
        setSpacing(false);

        H4 title = new H4("Files");
        title.getStyle().set("margin", "16px 0 4px 0");
        add(title);

        List<String> files = listFiles(resultsDir);
        if (files.isEmpty()) {
            add(new Div("no result files"));
            return;
        }

        Grid<String> grid = new Grid<>(String.class, false);
        grid.addColumn(name -> name).setHeader("path").setFlexGrow(1);
        grid.addColumn(name -> sizeOf(resultsDir, name)).setHeader("size")
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        grid.addComponentColumn(this::fileLink).setFlexGrow(0);
        grid.setItems(files);
        grid.setAllRowsVisible(true);
        grid.setMaxHeight("300px");
        add(grid);

        content.getStyle().set("margin-top", "8px");
        add(content);
    }

    private Component fileLink(String name) {
        String href = client.baseUrl() + "/runs/" + runId + "/files/" + name;
        Anchor anchor = new Anchor(href, isText(name) ? "view" : "open");
        anchor.getElement().setAttribute("target", "_blank");
        anchor.getElement().setAttribute("rel", "noopener");
        if (isText(name)) {
            anchor.getElement().addEventListener("click", e -> show(name))
                    .addEventData("event.preventDefault()");
        }
        return anchor;
    }

    void show(String name) {
        content.removeAll();
        String text;
        try {
            text = client.runFileText(runId, name);
        } catch (Exception e) {
            content.add(Panels.error(client.errorText(e)));
            return;
        }
        Div header = new Div(name);
        header.getStyle().set("font-weight", "600").set("margin-top", "8px");
        content.add(header, Panels.mono(truncateForDisplay(text)));
    }

    /** Caps inline file display; the full file is always one service link away. */
    static String truncateForDisplay(String text) {
        if (text.length() <= MAX_DISPLAY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DISPLAY_CHARS)
                + "\n\n… truncated at " + MAX_DISPLAY_CHARS + " chars — the full file is at the service link";
    }

    static boolean isText(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot));
    }

    private static String sizeOf(String resultsDir, String name) {
        try {
            long size = Files.size(Path.of(resultsDir, name.split("/")));
            return size < 1024 ? size + " B"
                    : size < 1024 * 1024 ? String.format("%.1f KiB", size / 1024.0)
                    : String.format("%.1f MiB", size / 1024.0 / 1024.0);
        } catch (IOException e) {
            return "–";
        }
    }

    /** Mirrors run_files() in the service's api.py: top-level files plus one level of subdirs. */
    static List<String> listFiles(String resultsDir) {
        if (resultsDir == null || resultsDir.isBlank()) {
            return List.of();
        }
        Path base = Path.of(resultsDir);
        if (!Files.isDirectory(base)) {
            return List.of();
        }
        List<String> files = new ArrayList<>();
        try (Stream<Path> top = Files.list(base)) {
            for (Path path : top.sorted().toList()) {
                String name = path.getFileName().toString();
                if (Files.isRegularFile(path)) {
                    files.add(name);
                } else if (Files.isDirectory(path) && !"workspace".equals(name)) {
                    try (Stream<Path> inner = Files.list(path)) {
                        inner.sorted()
                                .filter(Files::isRegularFile)
                                .forEach(child -> files.add(name + "/" + child.getFileName()));
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return files;
    }
}
