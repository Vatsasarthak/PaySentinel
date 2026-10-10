package com.pay.outbox.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guards the deployment environment ({@code payout.environment}, set by APP_ENVIRONMENT):
 * <ul>
 *   <li>Development-only features (the dev profile, ledger seeding, the loggers/env actuator endpoints)
 *       are permitted only when the environment is explicitly {@code local} (or {@code test}, set by the
 *       test profile). Anywhere else, including when APP_ENVIRONMENT is not set, startup is refused.</li>
 *   <li>{@code production} additionally refuses every safety-reducing setting.</li>
 * </ul>
 * Remaining findings are logged as warnings. The check runs before Flyway migrates, so a refused start
 * changes nothing.
 */
@Slf4j
@Component
public class ProductionSafetyCheck implements InitializingBean {

    /** Environments in which development-only features may be enabled. */
    static final Set<String> DEVELOPMENT_ENVIRONMENTS = Set.of("local", "test");

    private final PayoutProperties properties;
    private final Environment environment;

    public ProductionSafetyCheck(PayoutProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        String[] profiles = environment.getActiveProfiles();
        String exposed = environment.getProperty("management.endpoints.web.exposure.include", "");
        String deployment = normalise(properties.getEnvironment());

        List<String> developmentFeatures = developmentFeatures(properties, profiles, exposed);
        List<String> findings = findings(properties, profiles, exposed);
        if ("production".equals(deployment) && !findings.isEmpty()) {
            throw new IllegalStateException("Unsafe configuration for production: " + String.join("; ", findings));
        }
        if (!DEVELOPMENT_ENVIRONMENTS.contains(deployment) && !developmentFeatures.isEmpty()) {
            throw new IllegalStateException("Development-only features require APP_ENVIRONMENT=local (current: "
                    + (deployment.isEmpty() ? "not set" : deployment) + "): " + String.join("; ", developmentFeatures));
        }
        findings.forEach(finding -> log.warn("Development setting active: {}", finding));
    }

    /** Runs the safety check before any database migration is applied. */
    @Configuration(proxyBeanMethods = false)
    static class MigrationGuard {
        @Bean
        FlywayMigrationStrategy safetyCheckedMigration(ProductionSafetyCheck check) {
            // Depending on the check forces it to initialise (and refuse an unsafe start)
            // before any migration runs.
            return flyway -> flyway.migrate();
        }
    }

    /** Features that only belong on a developer machine. */
    static List<String> developmentFeatures(PayoutProperties properties, String[] activeProfiles,
                                            String exposedEndpoints) {
        List<String> features = new ArrayList<>();
        if (Arrays.asList(activeProfiles).contains("dev")) {
            features.add("the dev profile is active");
        }
        if (properties.getLedger().isSeedEnabled()) {
            features.add("ledger seeding is enabled (payout.ledger.seed-enabled)");
        }
        List<String> exposed = Arrays.asList(exposedEndpoints.replace(" ", "").split(","));
        if (exposed.contains("*") || exposed.contains("loggers") || exposed.contains("env")) {
            features.add("sensitive actuator endpoints are exposed (" + exposedEndpoints + ")");
        }
        return features;
    }

    /** Every development-only feature and safety-reducing setting; all of them are refused in production. */
    static List<String> findings(PayoutProperties properties, String[] activeProfiles, String exposedEndpoints) {
        List<String> findings = new ArrayList<>(developmentFeatures(properties, activeProfiles, exposedEndpoints));
        if (!properties.getFunding().isRequireApproval()) {
            findings.add("funding adjustments post without a second approval (payout.funding.require-approval=false)");
        }
        if (!properties.getRateLimit().isEnabled()) {
            findings.add("rate limiting is disabled (payout.rate-limit.enabled=false)");
        }
        if (!properties.getLedger().isFailOnUnmigratedLegacyData()) {
            findings.add("payout.ledger.fail-on-unmigrated-legacy-data=false");
        }
        return findings;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
