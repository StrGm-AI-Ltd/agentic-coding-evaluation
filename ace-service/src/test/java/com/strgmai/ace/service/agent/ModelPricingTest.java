package com.strgmai.ace.service.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** #328: a known model's usage must turn into the right dollar figure; an unknown model or missing
 *  usage must report null (no cost shown), never a guessed number. */
class ModelPricingTest {

    @Test
    void knownModelComputesInputAndOutputCostSeparately() {
        // gpt-4o: $2.50/M input, $10.00/M output
        final Double cost = ModelPricing.costUsd("gpt-4o", Map.of("input", 100_000L, "output", 50_000L));
        assertEquals(0.75, cost, 1e-9);   // 0.1 x 2.50 + 0.05 x 10.00
    }

    @Test
    void theLookupIsCaseInsensitive() {
        assertEquals(ModelPricing.costUsd("gpt-4o", Map.of("input", 1_000_000L, "output", 0L)),
                ModelPricing.costUsd("GPT-4o", Map.of("input", 1_000_000L, "output", 0L)));
    }

    @Test
    void anUnknownModelReportsNullNotAGuess() {
        assertNull(ModelPricing.costUsd("some-model-not-in-the-table", Map.of("input", 1_000L, "output", 1_000L)));
    }

    @Test
    void aNullModelOrUsageReportsNull() {
        assertNull(ModelPricing.costUsd(null, Map.of("input", 1_000L, "output", 1_000L)));
        assertNull(ModelPricing.costUsd("gpt-4o", null));
    }

    @Test
    void missingTokenCountsInUsageAreTreatedAsZero() {
        assertEquals(0.0, ModelPricing.costUsd("gpt-4o", Map.of()));
    }

    @Test
    void nebiusNemotronModelsHaveKnownRates() {
        // the hackathon-reviewer positioning's own target model
        assertEquals(0.30, ModelPricing.costUsd("nvidia/nemotron-3-super-120b-a12b", Map.of("input", 1_000_000L, "output", 0L)));
    }

    @Test
    void nebiusGlmFlashHasAKnownRate() {
        // zai-org/GLM-5.3-Flash: $0.15/M input, $0.50/M output
        final Double cost = ModelPricing.costUsd("zai-org/glm-5.3-flash", Map.of("input", 1_000_000L, "output", 1_000_000L));
        assertEquals(0.65, cost, 1e-9);
    }
}
