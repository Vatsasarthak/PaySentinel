package com.pay.outbox.security;

import com.pay.outbox.config.PayoutProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Applies per-key rate limits after authentication: payout creation, payout reads, and
 * administrative/treasury endpoints each have their own bucket per API key.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String PAYOUTS = "/api/v1/payouts";

    private final RateLimiter rateLimiter;
    private final PayoutProperties properties;
    private final ProblemDetailSecurityHandlers problemHandlers;

    public RateLimitFilter(RateLimiter rateLimiter, PayoutProperties properties,
                           ProblemDetailSecurityHandlers problemHandlers) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.problemHandlers = problemHandlers;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        PayoutProperties.RateLimit config = properties.getRateLimit();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String policyName = config.isEnabled() ? classify(request) : null;
        if (policyName == null || authentication == null
                || !(authentication.getPrincipal() instanceof ApiPrincipal principal)) {
            chain.doFilter(request, response);
            return;
        }

        PayoutProperties.Policy policy = switch (policyName) {
            case "create" -> config.getCreate();
            case "read" -> config.getRead();
            default -> config.getAdmin();
        };
        RateLimiter.Decision decision = rateLimiter.tryAcquire(policyName, "client:" + principal.name(), policy);
        if (decision.allowed()) {
            chain.doFilter(request, response);
        } else if (decision.limiterFailed()) {
            problemHandlers.rateLimiterUnavailable(request, response);
        } else {
            problemHandlers.tooManyRequests(request, response, decision.retryAfterMillis());
        }
    }

    static String classify(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(PAYOUTS + "/ledger") || path.equals(PAYOUTS + "/dlq") || path.startsWith("/api/v1/treasury")
                || (path.startsWith("/actuator") && !path.startsWith("/actuator/health")
                && !path.startsWith("/actuator/info"))) {
            return "admin";
        }
        if (path.equals(PAYOUTS) && HttpMethod.POST.matches(request.getMethod())) {
            return "create";
        }
        if (path.startsWith(PAYOUTS + "/") && HttpMethod.GET.matches(request.getMethod())) {
            return "read";
        }
        return null;
    }
}
