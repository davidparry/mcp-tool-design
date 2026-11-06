package com.davidparry.tooldesign.endpoint;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("endpoint")
public record EndpointProperties(Api api, Catalog catalog) {

    /** Where the OpenAPI document and the API live, the key the server calls with, and how long to wait for them. */
    public record Api(String baseUrl, String key, @DefaultValue("60s") Duration startupWait) {
    }

    /**
     * {@code verify} refuses to start when the generated catalog's hash differs from the snapshot; {@code write}
     * replaces the snapshot; {@code off} does neither.
     */
    public record Catalog(@DefaultValue("off") Mode mode, String snapshot) {
    }

    public enum Mode {
        OFF, WRITE, VERIFY
    }
}
