package com.agentbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** The Java/Spring/LangChain4j port of agentbench-trading: one runnable app serving the run queue,
 *  results store, oracle and the LangChain4j reference agent. The Python original's CLI
 *  (runner/run_bench.py) maps onto the queued job path: the worker runs benchmarks in-process. */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AgentbenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentbenchApplication.class, args);
    }
}
