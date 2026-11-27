package com.strgmai.ace.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational settings: timeouts, polling, tolerances and caps that depend on the machine and on how the operator runs
 * ACE, not on the benchmark. Defaults are in {@code application.yml} under {@code ace.operations}. The harness's own
 * limits and thresholds, which change scores, stay constants: they're part of {@link BenchProperties#HARNESS_VERSION}
 * and the comparability key, and change only through code.
 *
 * @param streamIdleTimeoutSec abandon a model stream that has produced no new output for this long
 * @param streamPollMs how often a call waiting on the model checks for cancellation and idleness
 * @param gradleTimeoutSec the oracle's Gradle build and test steps
 * @param proxyTimeoutMarginSec added to a job's first-token timeout for the proxy's own upstream wait
 * @param dockerSidecarReadyTimeoutSec how long a job's Docker sidecar may take to answer on its port
 * @param transcriptTailCapChars how much of a task's transcript a transcript request returns
 * @param requestSpeedWindowToleranceSec how far outside an attempt's window a request still counts for its speeds
 * @param judgeStampToleranceSec how far outside an attempt's window a judge verdict still counts for it
 * @param stuckWorkerThresholdSec how long a busy worker may log nothing before it's presumed wedged
 */
@ConfigurationProperties(prefix = "ace.operations")
public record OperationsProperties(
        int streamIdleTimeoutSec,
        int streamPollMs,
        int gradleTimeoutSec,
        int proxyTimeoutMarginSec,
        int dockerSidecarReadyTimeoutSec,
        int transcriptTailCapChars,
        int requestSpeedWindowToleranceSec,
        int judgeStampToleranceSec,
        int stuckWorkerThresholdSec) {
}
