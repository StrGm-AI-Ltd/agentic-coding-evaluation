package com.agentbench.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.io.Serializable;
import java.time.Duration;

/**
 * Location and HTTP budgets of the agentbench-trading FastAPI service
 * (service/ in agentbench-trading-service). Serializable so views can hold
 * the ServiceClient across UI session serialization (V-3).
 */
@ConfigurationProperties(prefix = "agentbench.service")
public record ServiceProperties(
        @DefaultValue("http://127.0.0.1:8765") String baseUrl,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("15s") Duration readTimeout) implements Serializable {
}
