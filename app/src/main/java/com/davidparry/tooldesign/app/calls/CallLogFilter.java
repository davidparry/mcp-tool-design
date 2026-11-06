package com.davidparry.tooldesign.app.calls;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Logs every {@code /api} call with its method, path, status, duration, caller, and run ID, refused calls included. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CallLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("api.calls");

    private final CallLog calls;

    public CallLogFilter(CallLog calls) {
        this.calls = calls;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        }
        finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            Object caller = request.getAttribute(ApiKeyFilter.CALLER);
            int status = failed ? 500 : response.getStatus();
            CallLog.Entry entry = this.calls.record(caller == null ? null : caller.toString(), request.getMethod(),
                    request.getRequestURI(), request.getQueryString(), status, ms);
            log.info("run={} caller={} {} {}{} {} {}ms", entry.runId(), entry.caller(), entry.method(), entry.path(),
                    entry.query() == null ? "" : "?" + entry.query(), entry.status(), entry.durationMs());
        }
    }
}
