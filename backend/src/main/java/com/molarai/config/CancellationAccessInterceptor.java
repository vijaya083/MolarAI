package com.molarai.config;

import com.molarai.service.CancellationDisabledException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

public class CancellationAccessInterceptor implements HandlerInterceptor {
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> CANCELLATION_PATHS = List.of(
            "/api/appointments/slots/*/booking",
            "/api/appointments/cancellation-requests/*",
            "/api/appointments/cancellation-requests/*/resend",
            "/api/appointments/cancellation-requests/*/verify");

    private final boolean enabled;

    public CancellationAccessInterceptor(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled && isCancellationEndpoint(request.getMethod(), request.getRequestURI())) {
            throw new CancellationDisabledException();
        }
        return true;
    }

    private boolean isCancellationEndpoint(String method, String path) {
        if ("POST".equals(method)
                && ("/api/appointments/cancellation-matches".equals(path)
                || "/api/appointments/cancellation-requests".equals(path))) {
            return true;
        }
        if ("DELETE".equals(method)) {
            return PATH_MATCHER.match("/api/appointments/slots/*/booking", path)
                    || PATH_MATCHER.match("/api/appointments/cancellation-requests/*", path);
        }
        return "POST".equals(method)
                && (PATH_MATCHER.match("/api/appointments/cancellation-requests/*/resend", path)
                || PATH_MATCHER.match("/api/appointments/cancellation-requests/*/verify", path));
    }
}
