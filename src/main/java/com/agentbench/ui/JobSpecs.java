package com.agentbench.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Normalizes the job form's raw field values into a POST /api/jobs spec map (C-1,
 * testability): snake_case keys matching queue.py's RunSpec, blank strings and
 * nulls dropped, boolean flags always present (matching the service's own form,
 * which sends {name: name in form} for every BOOL_FIELD), task required.
 */
public final class JobSpecs {

    /** RunSpec's boolean flags, in queue.py order. */
    public static final Set<String> BOOLEAN_FLAGS = Set.of(
            "handoff_notes", "system_rules", "self_review", "review_blind", "trajectory_review",
            "no_context_probe", "context_probe_fresh", "keep_workspace", "manage_docker", "skip_docker");

    private JobSpecs() {
    }

    /**
     * @param task  the required rung
     * @param raw   field values by RunSpec key (Strings/Integers/Doubles/Booleans, may be null or blank);
     *              a null map is treated as empty
     * @return the normalized spec map; task first
     * @throws IllegalArgumentException when task is blank
     */
    public static Map<String, Object> build(String task, Map<String, Object> raw) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required (a rung from tasks/ladder.json)");
        }
        if (raw == null) {
            raw = Map.of();
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("task", task);
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (BOOLEAN_FLAGS.contains(key)) {
                spec.put(key, Boolean.TRUE.equals(value));
            } else if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                spec.put(key, value);
            }
        }
        return spec;
    }
}
