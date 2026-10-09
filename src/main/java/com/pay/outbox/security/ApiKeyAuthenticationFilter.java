package com.pay.outbox.security;

import com.pay.outbox.config.PayoutProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Authenticates requests carrying an {@code X-API-Key} header. Keys are compared as SHA-256
 * digests in constant time, so only digests need to be configured. Requests without the header
 * continue anonymously and are rejected by the authorization rules. Requests with an unknown key are
 * rejected with 401, and with 429 once the client IP exceeds the auth-failure rate limit.
 */
@Slf4j
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    public record Credential(ApiPrincipal principal, byte[] keyDigest) {
    }

    private final List<Credential> credentials;
    private final ProblemDetailSecurityHandlers problemHandlers;
    private final RateLimiter rateLimiter;
    private final PayoutProperties properties;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(List<Credential> credentials, ProblemDetailSecurityHandlers problemHandlers,
                                      RateLimiter rateLimiter, PayoutProperties properties) {
        this.credentials = List.copyOf(credentials);
        this.problemHandlers = problemHandlers;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null) {
            chain.doFilter(request, response);
            return;
        }

        byte[] digest = sha256(presented);
        ApiPrincipal match = null;
        for (Credential credential : credentials) {
            // Compare against every key so timing does not reveal which one matched.
            if (MessageDigest.isEqual(digest, credential.keyDigest())) {
                match = credential.principal();
            }
        }
        if (match == null) {
            contextHolder.clearContext();
            log.warn("Rejected request with an invalid API key from {}", request.getRemoteAddr());
            if (properties.getRateLimit().isEnabled()) {
                RateLimiter.Decision decision = rateLimiter.tryAcquire("auth-failure", "ip:" + request.getRemoteAddr(),
                        properties.getRateLimit().getAuthFailure());
                if (!decision.allowed() && !decision.limiterFailed()) {
                    problemHandlers.tooManyRequests(request, response, decision.retryAfterMillis());
                    return;
                }
            }
            problemHandlers.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                match, null, List.of(new SimpleGrantedAuthority("ROLE_" + match.role()))));
        contextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            contextHolder.clearContext();
        }
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
