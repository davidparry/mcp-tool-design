package com.davidparry.tooldesign.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class WorkflowMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowMcpServerApplication.class, args);
    }
}
