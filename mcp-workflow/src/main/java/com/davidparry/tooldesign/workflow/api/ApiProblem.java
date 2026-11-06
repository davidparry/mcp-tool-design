package com.davidparry.tooldesign.workflow.api;

import java.util.Map;

/** A refusal from the order API: its HTTP status, its {@code code}, its detail, and any facts it attached. */
public class ApiProblem extends RuntimeException {

    private final int status;

    private final String code;

    private final Map<String, Object> properties;

    public ApiProblem(int status, String code, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public int status() {
        return this.status;
    }

    public String code() {
        return this.code;
    }

    public String detail() {
        return getMessage();
    }

    public Object property(String name) {
        return this.properties.get(name);
    }
}
