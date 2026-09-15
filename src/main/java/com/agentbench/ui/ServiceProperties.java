package com.agentbench.ui;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Location of the agentbench-trading FastAPI service (service/ in agentbench-trading-service).
 */
@ConfigurationProperties(prefix = "agentbench.service")
public record ServiceProperties(@DefaultValue("http://127.0.0.1:8765") String baseUrl) {
}
