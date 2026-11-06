package com.davidparry.tooldesign.app.calls;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api} needs one of the configured API keys in {@code X-Api-Key}; {@code /admin} needs the admin key in
 * {@code X-Admin-Key}. The key's name, such as {@code support-agent}, becomes the caller in the call log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    static final String CALLER = ApiKeyFilter.class.getName() + ".caller";

    private final ApiKeys keys;

    private final JsonMapper json;

    public ApiKeyFilter(ApiKeys keys, JsonMapper json) {
        this.keys = keys;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") && !path.startsWith("/admin/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/admin/")) {
            if (!matches(this.keys.adminKey(), request.getHeader("X-Admin-Key"))) {
                refuse(request, response, "The admin endpoints need the admin key in X-Admin-Key");
                return;
            }
            chain.doFilter(request, response);
            return;
        }
        Optional<String> caller = caller(request.getHeader("X-Api-Key"));
        if (caller.isEmpty()) {
            refuse(request, response, "The API needs a valid key in X-Api-Key");
            return;
        }
        request.setAttribute(CALLER, caller.get());
        chain.doFilter(request, response);
    }

    private Optional<String> caller(String presented) {
        return this.keys.apiKeys().entrySet().stream().filter(entry -> matches(entry.getValue(), presented))
                .map(Map.Entry::getKey).findFirst();
    }

    private static boolean matches(String expected, String presented) {
        return presented != null && expected != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", HttpStatus.UNAUTHORIZED.getReasonPhrase());
        problem.put("status", HttpStatus.UNAUTHORIZED.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        problem.put("code", "unauthorized");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(this.json.writeValueAsString(problem));
    }
}
