package com.agentbench.ui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;

@SpringBootApplication
@ConfigurationPropertiesScan
@StyleSheet("styles.css")
public class AgentBenchUiApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(AgentBenchUiApplication.class, args);
    }
}
