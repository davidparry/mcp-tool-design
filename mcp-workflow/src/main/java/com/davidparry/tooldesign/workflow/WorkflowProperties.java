package com.davidparry.tooldesign.workflow;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where the order API is, the key this server calls it with, and how many options a preview returns. */
@ConfigurationProperties("workflow")
public record WorkflowProperties(Api api, @DefaultValue("3") int optionLimit, @DefaultValue("5") int orderLimit) {

    public record Api(String baseUrl, String key) {
    }
}
