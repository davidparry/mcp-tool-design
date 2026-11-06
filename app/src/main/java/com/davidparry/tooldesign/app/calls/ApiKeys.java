package com.davidparry.tooldesign.app.calls;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** API keys by caller name, and the separate admin key. */
@ConfigurationProperties("app.security")
public record ApiKeys(Map<String, String> apiKeys, String adminKey) {
}
