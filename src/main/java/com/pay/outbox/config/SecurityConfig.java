package com.pay.outbox.config;

import com.pay.outbox.dto.ValidationPatterns;
import com.pay.outbox.security.ApiKeyAuthenticationFilter;
import com.pay.outbox.security.ApiKeyAuthenticationFilter.Credential;
import com.pay.outbox.security.ApiPrincipal;
import com.pay.outbox.security.ProblemDetailSecurityHandlers;
import com.pay.outbox.security.RateLimitFilter;
import com.pay.outbox.security.RateLimiter;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stateless API-key security.
 * <ul>
 *   <li>CLIENT keys may create and read payouts only for the ledger accounts they are granted.</li>
 *   <li>The ADMIN key may use the ledger, dead-letter and protected actuator endpoints. It does not
 *       bypass account ownership: it can create payouts only for accounts explicitly granted to it,
 *       and read all payouts only if {@code payout.security.admin.read-all-payouts=true}.</li>
 * </ul>
 * Keys (or their SHA-256 digests) come from the environment; the application refuses to start
 * with a missing, short, placeholder or duplicated key.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final int MIN_KEY_LENGTH = 32;
    private static final Pattern CLIENT_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern ACCOUNT_ID = Pattern.compile(ValidationPatterns.ACCOUNT_ID);
    private static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
    /** Reserved: "legacy" owns payouts created before client scoping; "admin" is the admin principal. */
    private static final Set<String> RESERVED_NAMES = Set.of("legacy", "admin");

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, PayoutProperties properties,
                                                   ProblemDetailSecurityHandlers problemHandlers,
                                                   RateLimiter rateLimiter) throws Exception {
        ApiKeyAuthenticationFilter apiKeyFilter =
                new ApiKeyAuthenticationFilter(credentials(properties), problemHandlers, rateLimiter, properties);
        RateLimitFilter rateLimitFilter = new RateLimitFilter(rateLimiter, properties, problemHandlers);

        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problemHandlers)
                        .accessDeniedHandler(problemHandlers))
                .addFilterBefore(apiKeyFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(rateLimitFilter, ApiKeyAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class, InfoEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        .requestMatchers("/api/v1/payouts/ledger/**", "/api/v1/payouts/dlq").hasRole("ADMIN")
                        // Account ownership for payouts is enforced in PayoutController.
                        .requestMatchers(HttpMethod.POST, "/api/v1/payouts").hasAnyRole("CLIENT", "ADMIN")
                        .requestMatchers("/api/v1/payouts/**").hasAnyRole("CLIENT", "ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    /** Replaces Spring Boot's generated default user; authentication is API-key only. */
    @Bean
    public UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    static List<Credential> credentials(PayoutProperties properties) {
        PayoutProperties.Security security = properties.getSecurity();
        List<Credential> credentials = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (PayoutProperties.Client client : security.getClients()) {
            String name = client.getName();
            if (name == null || !CLIENT_NAME.matcher(name).matches()) {
                throw new IllegalStateException("payout.security.clients[].name must match " + CLIENT_NAME.pattern());
            }
            if (RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT)) || !names.add(name)) {
                throw new IllegalStateException("API client name '" + name + "' is reserved or duplicated");
            }
            String label = "API key of client '" + name + "'";
            byte[] digest = keyDigest(label, client.getApiKey(), client.getApiKeySha256());
            credentials.add(new Credential(new ApiPrincipal(name, "CLIENT", accounts(label, client.getAccounts()), false),
                    digest));
        }
        if (credentials.isEmpty()) {
            throw new IllegalStateException("At least one API client must be configured (payout.security.clients)");
        }

        PayoutProperties.Admin admin = security.getAdmin();
        String label = "admin API key";
        credentials.add(new Credential(new ApiPrincipal("admin", "ADMIN", accounts(label, admin.getAccounts()),
                admin.isReadAllPayouts()), keyDigest(label, admin.getApiKey(), admin.getApiKeySha256())));

        Set<String> digests = new HashSet<>();
        for (Credential credential : credentials) {
            if (!digests.add(HexFormat.of().formatHex(credential.keyDigest()))) {
                throw new IllegalStateException("Every API key must be different");
            }
        }
        return credentials;
    }

    private static Set<String> accounts(String label, List<String> configured) {
        Set<String> accounts = new LinkedHashSet<>();
        for (String account : configured) {
            String trimmed = account == null ? "" : account.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!ACCOUNT_ID.matcher(trimmed).matches()) {
                throw new IllegalStateException("Invalid account id '" + trimmed + "' granted to " + label);
            }
            accounts.add(trimmed);
        }
        return accounts;
    }

    private static byte[] keyDigest(String label, String plainKey, String sha256Hex) {
        boolean hasPlain = plainKey != null && !plainKey.isBlank();
        boolean hasHash = sha256Hex != null && !sha256Hex.isBlank();
        if (hasPlain == hasHash) {
            throw new IllegalStateException(label + ": set exactly one of api-key or api-key-sha256");
        }
        if (hasHash) {
            if (!SHA256_HEX.matcher(sha256Hex.trim()).matches()) {
                throw new IllegalStateException(label + ": api-key-sha256 must be 64 hex characters");
            }
            return HexFormat.of().parseHex(sha256Hex.trim().toLowerCase(Locale.ROOT));
        }
        if (plainKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(label + " must be at least " + MIN_KEY_LENGTH + " characters");
        }
        String lower = plainKey.toLowerCase(Locale.ROOT);
        if (lower.startsWith("replace-with") || lower.startsWith("change-me")) {
            throw new IllegalStateException(label + " still contains the placeholder from .env.example");
        }
        return ApiKeyAuthenticationFilter.sha256(plainKey);
    }
}
