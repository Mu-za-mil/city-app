package com.cityapp.common.logging;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds contextual fields to every log message via MDC (Mapped Diagnostic Context).
 *
 * WHY MDC:
 *   Without MDC: log message for order failure: "Order payment failed"
 *   No context. Which order? Which user? Which request?
 *
 *   With MDC: every log message automatically includes:
 *   {"message": "Order payment failed",
 *    "requestId": "abc-123", "userId": "42", "traceId": "xyz-456"}
 *
 *   In Loki: filter by userId=42 → see ALL log messages for that user
 *   in that request across all log levels and classes.
 *
 * HOW MDC WORKS:
 *   MDC is a thread-local map. Set key-value pairs once in the filter.
 *   ALL log.info/warn/error calls in that thread automatically include those pairs.
 *   LogstashEncoder: includes all MDC entries in the JSON output.
 *   Filter clears MDC after request: prevents stale values in pooled threads.
 *
 * CORRELATION WITH TRACING:
 *   Micrometer Tracing automatically adds traceId and spanId to MDC.
 *   Our filter adds requestId, path, method.
 *   Combined: every log line has both trace context AND HTTP context.
 *   Loki query: {app="city-app"} | json | traceId="abc123" → all logs for that trace.
 */
@Slf4j
@Component
@Order(2)  // After RateLimitFilter (Order 1), before Spring Security
public class LoggingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  request  = (HttpServletRequest)  req;
        HttpServletResponse response = (HttpServletResponse) res;

        // Generate a request-unique ID for log correlation
        // (If traceId is available from Zipkin: use that instead)
        String requestId = UUID.randomUUID().toString().substring(0, 8);

        try {
            // Set MDC fields — available in ALL log messages for this request
            MDC.put("requestId", requestId);
            MDC.put("method",    request.getMethod());
            MDC.put("path",      request.getRequestURI());
            MDC.put("ip",        request.getRemoteAddr());

            // Add request ID to response header (for client-side correlation)
            response.setHeader("X-Request-Id", requestId);

            long start = System.currentTimeMillis();
            chain.doFilter(req, res);
            long duration = System.currentTimeMillis() - start;

            // Log the completed request
            log.info("HTTP {} {} → {} ({}ms)",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    duration);

        } finally {
            // CRITICAL: clear MDC after every request.
            // Threads are reused from the pool.
            // Without clear: the next request inherits the previous request's MDC.
            // User B sees User A's userId in their logs. Wrong and confusing.
            MDC.clear();
        }
    }
}
