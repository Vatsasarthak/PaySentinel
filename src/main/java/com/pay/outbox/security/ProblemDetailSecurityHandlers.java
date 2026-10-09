package com.pay.outbox.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

/** Writes security and rate-limit errors as RFC 7807 problem details, matching the API's other errors. */
@Component
public class ProblemDetailSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String BASE_URL = "https://outbox-payments.com/errors";

    private final ObjectMapper objectMapper;

    public ProblemDetailSecurityHandlers(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + ApiKeyAuthenticationFilter.HEADER + "\"");
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized", "unauthorized",
                "A valid API key is required in the " + ApiKeyAuthenticationFilter.HEADER + " header");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden", "forbidden",
                "The API key is not authorized for this operation");
    }

    public void tooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfterMillis)
            throws IOException {
        long seconds = Math.max(1, (retryAfterMillis + 999) / 1000);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "rate-limited",
                "Rate limit exceeded; retry after " + seconds + " seconds");
    }

    public void rateLimiterUnavailable(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "rate-limiter-unavailable",
                "The request could not be admitted; please retry");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String title, String type, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(BASE_URL + "/" + type));
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
