package com.agentbench.plan;

public class PlanError extends RuntimeException {
    public PlanError(String message) { super(message); }
    public PlanError(String message, Throwable cause) { super(message, cause); }   // preserve the root-cause chain when wrapping
}
