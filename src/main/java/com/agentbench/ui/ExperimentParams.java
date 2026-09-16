package com.agentbench.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes the experiment form's raw field values into a POST /api/experiments
 * params map, one shape per template (experiments.py TEMPLATE_PARAMS): harness_effect
 * (model, arms, parallel), model_ab (model_a, model_b), agent_ab (model, mode, agents),
 * plus the shared ReviewParams/ContextParams. Pure and unit-testable (C-1, J-3).
 */
public final class ExperimentParams {

    private ExperimentParams() {
    }

    /**
     * @param raw field values by param key; task_tokens as a String ("auto" or digits)
     * @return the params map for the template
     * @throws IllegalArgumentException with a user-presentable message on bad input
     */
    public static Map<String, Object> build(String template, Map<String, Object> raw) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task_wall", intOr(raw.get("task_wall"), 3600, "task_wall"));
        params.put("task_tokens", taskTokens(raw.get("task_tokens")));
        if (raw.get("context_window") != null) {
            params.put("context_window", intOr(raw.get("context_window"), null, "context_window"));
        }
        putIfPresent(params, raw, "no_context_probe", Boolean.class);
        putIfPresent(params, raw, "reviewer_model", String.class);
        putIfPresent(params, raw, "review_weight", Double.class);
        putIfPresent(params, raw, "review_blind", Boolean.class);
        putIfPresent(params, raw, "trajectory_reviewer_model", String.class);
        putIfPresent(params, raw, "trajectory_weight", Double.class);
        putIfPresent(params, raw, "trajectory_use", String.class);

        switch (template) {
            case "harness_effect" -> {
                requireModel((String) raw.get("model"));
                params.put("model", raw.get("model"));
                List<String> arms = new ArrayList<>();
                for (Object arm : (List<?>) raw.getOrDefault("arms", List.of())) {
                    arms.add(String.valueOf(arm));
                }
                if (arms.isEmpty()) { // the service defaults to orch+mono when nothing is checked
                    arms = List.of("orch", "mono");
                }
                params.put("arms", arms);
                params.put("parallel", intOr(raw.get("parallel"), 3, "parallel"));
            }
            case "model_ab" -> {
                if (blank(raw.get("model_a")) || blank(raw.get("model_b"))) {
                    throw new IllegalArgumentException("model_ab needs both model_a and model_b");
                }
                params.put("model_a", raw.get("model_a"));
                params.put("model_b", raw.get("model_b"));
            }
            case "agent_ab" -> {
                requireModel((String) raw.get("model"));
                params.put("model", raw.get("model"));
                params.put("mode", raw.get("mode") == null ? "orchestrated" : raw.get("mode"));
                Object agents = raw.get("agents");
                params.put("agents", agents == null ? List.of("ref", "pi") : agents);
            }
            default -> throw new IllegalArgumentException("unknown template: " + template);
        }
        return params;
    }

    /** "auto" (or blank) stays "auto"; digit strings become Integers; anything else is rejected. */
    static Object taskTokens(Object value) {
        if (value == null || blank(value) || "auto".equals(value)) {
            return "auto";
        }
        if (value instanceof Integer number) {
            return number;
        }
        String text = value.toString();
        if (text.matches("\\d+")) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("task_tokens: enter a number up to 2147483647, or 'auto'");
            }
        }
        throw new IllegalArgumentException("task_tokens: enter a number, or 'auto'");
    }

    private static void requireModel(String model) {
        if (blank(model)) {
            throw new IllegalArgumentException("a model is required for every template");
        }
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String text && text.isBlank());
    }

    private static Object intOr(Object value, Integer fallback, String field) {
        if (value == null || blank(value)) {
            return fallback;
        }
        if (value instanceof Integer number) {
            return number;
        }
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + ": enter a whole number");
        }
    }

    private static void putIfPresent(Map<String, Object> params, Map<String, Object> raw,
            String key, Class<?> type) {
        Object value = raw.get(key);
        if (value == null || blank(value)) {
            return;
        }
        params.put(key, type.cast(value));
    }
}
