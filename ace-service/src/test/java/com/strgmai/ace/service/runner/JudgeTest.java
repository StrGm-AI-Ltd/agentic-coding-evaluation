package com.strgmai.ace.service.runner;

import com.strgmai.ace.service.agent.ReferenceAgent;
import com.strgmai.ace.service.config.BenchProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #265: cap()/capTail() are the one thing standing between the judge's own prompt and becoming
 *  the next oversized-context failure (#256's own original shape) - pinned directly, same
 *  reasoning ReviewsTest already applies to externalBase()/externalCredentials() (which Judge
 *  deliberately duplicates rather than shares - see Judge's own class javadoc). */
class JudgeTest {

    private final BenchProperties props = new BenchProperties(null, "http://127.0.0.1:9191/v1", null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null);
    private final Judge judge = new Judge(props,
            new ReferenceAgent(new BenchProperties(null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null)));

    @Test
    void capLeavesShortTextUntouchedAndTruncatesLongTextWithAVisibleMarker() {
        assertEquals("short", Judge.cap("short", 100));
        final var truncated = Judge.cap("0123456789", 5);
        assertTrue(truncated.startsWith("01234"));
        assertTrue(truncated.contains("truncated at 5 chars"));
    }

    @Test
    void capOfNullIsBlankNotAnNpe() {
        assertEquals("", Judge.cap(null, 100));
    }

    @Test
    void capTailKeepsTheNewestPartNotTheOldest() {
        final var kept = Judge.capTail("0123456789", 5);
        assertTrue(kept.endsWith("56789"), "the tail (newest part) must survive: " + kept);
        assertTrue(kept.contains("older chars omitted"));
        assertEquals("short", Judge.capTail("short", 100));
    }

    @Test
    void externalBaseResolvesAKnownProviderPrefix() {
        assertEquals("https://api.openai.com/v1", judge.externalBase("openai/gpt-4", Map.of()));
    }

    @Test
    void externalBaseFallsBackToTheConfiguredEndpointForAnUnknownProvider() {
        assertEquals("http://127.0.0.1:9191/v1", judge.externalBase("localmodel/whatever", Map.of()));
    }

    /** #295: the twin of ReviewsTest's own equivalent case - see Judge.externalBase()'s comment. */
    @Test
    void externalBaseDoesNotThrowOnAReviewerStringWithNoProviderPrefixAtAll() {
        assertEquals("http://127.0.0.1:9191/v1", judge.externalBase("bare-model-name-no-slash", Map.of()));
    }

    @Test
    void externalCredentialsHonoursAnExplicitCredentialEnvOverride() {
        final var extra = judge.externalCredentials(Map.of("credential_env", List.of("PATH")));
        assertTrue(extra.containsKey("PATH"));
    }

    // ---- #267: renderTrajectory() - the one piece of the prompt-construction pipeline that is
    // both pure (no I/O) and the most likely to silently mis-render a message type

    @Test
    void renderTrajectoryRendersAUserMessageWithItsOwnLabel() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.UserMessage.from("do the task")));
        assertTrue(out.contains("USER: do the task"), out);
    }

    @Test
    void renderTrajectoryRendersAnAssistantTextMessage() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from("thinking out loud")));
        assertTrue(out.contains("ASSISTANT: thinking out loud"), out);
    }

    @Test
    void renderTrajectoryRendersAToolCallWithItsNameAndArguments() {
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{\"command\":\"ls\"}").build();
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from(List.of(req))));
        assertTrue(out.contains("ASSISTANT CALLED bash({\"command\":\"ls\"})"), out);
    }

    @Test
    void renderTrajectoryRendersAToolResultWithItsOwnToolNameAndCapsLongOutput() {
        final var req = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("c1").name("bash").arguments("{}").build();
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.ToolExecutionResultMessage.from(req, "x".repeat(2000))));
        assertTrue(out.contains("TOOL RESULT (bash):"), out);
        assertTrue(out.contains("truncated at 1000 chars"), "tool results must be capped, same reasoning as the prompt's own diff/trajectory caps: " + out);
    }

    @Test
    void renderTrajectorySkipsAnAiMessageWithNeitherTextNorToolCalls() {
        final var out = judge.renderTrajectory(List.of(dev.langchain4j.data.message.AiMessage.from("")));
        assertTrue(out.isBlank(), "a content-free assistant turn must render as nothing, not a stray label: " + out);
    }

    // ---- #267: checkpoint()'s own exception contract - a judge failure must never take the task
    // down with it (same reasoning Judge's own class javadoc states explicitly)

    /** Forces a REAL exception deep inside the wrapped runCheckpoint() call: `rd` is a plain FILE,
     *  not a directory, so agent.run()'s own AgentSession construction (which creates `rd/sessions`
     *  as a subdirectory) cannot possibly succeed - no network reachability assumed either way. */
    @Test
    void checkpointSwallowsAnInternalExceptionAndReturnsNullInsteadOfPropagatingIt(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path tmp) throws Exception {
        final var rdAsAFile = tmp.resolve("not-a-directory");
        java.nio.file.Files.writeString(rdAsAFile, "x");
        final var ws = tmp.resolve("ws");
        java.nio.file.Files.createDirectories(ws);

        // external=true (an unrecognised provider prefix) and a short wall_sec: no local proxy is
        // started, and even if this somehow reached a real network attempt, it would be bounded to
        // a couple of seconds rather than hanging the test - belt and suspenders alongside the
        // forced-exception path this test is actually targeting.
        final var checkpoint = judge.checkpoint(Map.of("model", "m"), "run-1", ws, rdAsAFile,
                new RecordingProxyFactory(props), "T1", "goal", 0L, 0L, System.currentTimeMillis(),
                Map.of("model", "unrecognised-provider/m", "wall_sec", 2));

        final String rec = org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> checkpoint.check(10, List.of()), "checkpoint() must swallow the exception, never propagate it to the caller's own loop");
        assertEquals(null, rec, "a judge failure must degrade to \"no recommendation,\" not throw");
    }
}
