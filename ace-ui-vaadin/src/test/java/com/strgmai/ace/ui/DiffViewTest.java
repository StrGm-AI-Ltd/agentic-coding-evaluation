package com.strgmai.ace.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #268, found live: DiffView.render() has real branching worth pinning down - every comparably
 *  sized, pure-rendering sibling in this package (Badges, Markdown) has its own test class, but
 *  DiffView, introduced alongside the judge feature's other new code, had none. Element's own
 *  getTextRecursively() (same approach BadgesTest already uses via getElement().getText() for a
 *  single element) is enough to assert on the rendered tree without a real browser. */
class DiffViewTest {

    private static String text(final com.vaadin.flow.component.Component c) {
        return c.getElement().getTextRecursively();
    }

    private static Api.TaskDiff diff(final boolean started, final boolean finished, final String beforeSha,
                                      final String afterSha, final String diffText, final boolean diffTruncated,
                                      final List<Api.NewFile> newFiles, final boolean newFilesTruncated, final String error) {
        return new Api.TaskDiff(started, finished, beforeSha, afterSha, diffText, diffTruncated, newFiles, newFilesTruncated, error);
    }

    @Test
    void notStartedShowsTheWorkspaceNotInitializedMessage() {
        final var rendered = text(DiffView.render(diff(false, false, null, null, null, false, List.of(), false, null)));
        assertTrue(rendered.contains("workspace not initialized yet"), rendered);
    }

    @Test
    void anErrorShowsThePanelsErrorMessageInsteadOfAnyDiffContent() {
        final var rendered = text(DiffView.render(diff(true, false, "abc1234", null, "+still here", false, List.of(), false, "could not read workspace")));
        assertTrue(rendered.contains("could not read workspace"), rendered);
        assertFalse(rendered.contains("still here"), "an error must short-circuit before any diff content is ever rendered: " + rendered);
    }

    @Test
    void noChangesAndNoNewFilesShowsTheNoChangesMessage() {
        final var rendered = text(DiffView.render(diff(true, true, "abc1234", "def5678", "", false, List.of(), false, null)));
        assertTrue(rendered.contains("no changes"), rendered);
    }

    @Test
    void aFinishedDiffShowsTheBeforeAfterShaCaptionNotTheInProgressWording() {
        final var rendered = text(DiffView.render(diff(true, true, "abc1234", "def5678", "+line", false, List.of(), false, null)));
        assertTrue(rendered.contains("abc1234"), rendered);
        assertTrue(rendered.contains("def5678"), rendered);
        assertFalse(rendered.contains("in progress"), "a FINISHED diff must not say in-progress: " + rendered);
    }

    @Test
    void anInProgressDiffShowsTheWorkingTreeInProgressWording() {
        final var rendered = text(DiffView.render(diff(true, false, "abc1234", null, "+line", false, List.of(), false, null)));
        assertTrue(rendered.contains("working tree (in progress)"), rendered);
        assertTrue(rendered.contains("abc1234"), rendered);
    }

    @Test
    void aShaShorterThanSevenCharsIsShownInFullNotPaddedOrTruncatedToAPlaceholder() {
        final var rendered = text(DiffView.render(diff(true, true, "ab1", "def5678", "+line", false, List.of(), false, null)));
        assertTrue(rendered.contains("ab1"), rendered);
    }

    @Test
    void aNullShaIsShownAsAQuestionMark() {
        final var rendered = text(DiffView.render(diff(true, false, null, null, "+line", false, List.of(), false, null)));
        assertTrue(rendered.contains("?"), rendered);
    }

    @Test
    void diffTextIsShownAndItsTruncationNoticeOnlyAppearsWhenActuallyTruncated() {
        final var notTruncated = text(DiffView.render(diff(true, true, "abc1234", "def5678", "+added line\n-removed line", false, List.of(), false, null)));
        assertTrue(notTruncated.contains("added line"), notTruncated);
        assertTrue(notTruncated.contains("removed line"), notTruncated);
        assertFalse(notTruncated.contains("truncated"), "no truncation notice when diff_truncated is false: " + notTruncated);

        final var truncated = text(DiffView.render(diff(true, true, "abc1234", "def5678", "+added line", true, List.of(), false, null)));
        assertTrue(truncated.contains("truncated"), "a truncation notice must appear when diff_truncated is true: " + truncated);
    }

    @Test
    void newFilesAreShownWithTheirOwnPathAndContentAndPerFileTruncationNotice() {
        final var newFiles = List.of(
                new Api.NewFile("src/main/Foo.java", "class Foo {}", false),
                new Api.NewFile("src/main/Bar.java", "class Bar { /* cut off", true));
        final var rendered = text(DiffView.render(diff(true, false, "abc1234", null, "", false, newFiles, false, null)));
        assertTrue(rendered.contains("src/main/Foo.java"), rendered);
        assertTrue(rendered.contains("class Foo {}"), rendered);
        assertTrue(rendered.contains("src/main/Bar.java"), rendered);
        assertTrue(rendered.contains("truncated"), "Bar.java's own truncation=true must produce a per-file notice: " + rendered);
    }

    @Test
    void newFilesTruncatedShowsTheMoreFilesExistNotice() {
        final var oneFile = List.of(new Api.NewFile("a.txt", "content", false));
        final var rendered = text(DiffView.render(diff(true, false, "abc1234", null, "", false, oneFile, true, null)));
        assertTrue(rendered.contains("more new files exist"), rendered);
    }

    @Test
    void newFilesAloneWithNoDiffTextStillRendersNotTheNoChangesMessage() {
        final var newFiles = List.of(new Api.NewFile("a.txt", "content", false));
        final var rendered = text(DiffView.render(diff(true, false, "abc1234", null, null, false, newFiles, false, null)));
        assertFalse(rendered.contains("no changes"), "new files alone are still a real change, not \"no changes\": " + rendered);
        assertTrue(rendered.contains("a.txt"));
    }
}
