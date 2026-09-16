package com.agentbench.ui;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServicePropertiesBindingTest {

    @EnableConfigurationProperties(ServiceProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    void bindsDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .run(context -> {
                    ServiceProperties properties = context.getBean(ServiceProperties.class);
                    assertEquals("http://127.0.0.1:8765", properties.baseUrl());
                    assertEquals(Duration.ofSeconds(2), properties.connectTimeout());
                    assertEquals(Duration.ofSeconds(15), properties.readTimeout());
                });
    }

    @Test
    void bindsOverrides() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "agentbench.service.base-url=http://10.0.0.5:9000",
                        "agentbench.service.connect-timeout=5s",
                        "agentbench.service.read-timeout=30s")
                .run(context -> {
                    ServiceProperties properties = context.getBean(ServiceProperties.class);
                    assertEquals("http://10.0.0.5:9000", properties.baseUrl());
                    assertEquals(Duration.ofSeconds(5), properties.connectTimeout());
                    assertEquals(Duration.ofSeconds(30), properties.readTimeout());
                });
    }
}
