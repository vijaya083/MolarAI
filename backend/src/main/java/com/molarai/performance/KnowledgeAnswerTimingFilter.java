package com.molarai.performance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class KnowledgeAnswerTimingFilter extends OncePerRequestFilter {
    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final PerformanceTiming timing;

    public KnowledgeAnswerTimingFilter(PerformanceTiming timing) {
        this.timing = timing;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/knowledge/answer".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String incomingId = request.getHeader(REQUEST_ID_HEADER);
        String requestId = incomingId != null && SAFE_REQUEST_ID.matcher(incomingId).matches()
                ? incomingId : UUID.randomUUID().toString();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        timing.beginRequest(requestId);
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failed = true;
            throw exception;
        } finally {
            timing.completeRequest(response.getStatus(), failed || response.getStatus() >= 500);
        }
    }
}
