package com.pay.outbox.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionSafetyCheckTest {

    private static final String SAFE_ENDPOINTS = "health, info, metrics";
    private static final String DEV_ENDPOINTS = "health, info, metrics, loggers";

    /** The configuration produced by the dev profile (application-dev.yaml) plus the given environment. */
    private static ProductionSafetyCheck devSetup(String deploymentEnvironment) {
        PayoutProperties properties = new PayoutProperties();
        properties.setEnvironment(deploymentEnvironment);
        properties.getLedger().setSeedEnabled(true);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", DEV_ENDPOINTS);
        environment.setActiveProfiles("dev");
        return new ProductionSafetyCheck(properties, environment);
    }

    private static ProductionSafetyCheck check(PayoutProperties properties, String exposedEndpoints) {
        return new ProductionSafetyCheck(properties, new MockEnvironment()
                .withProperty("management.endpoints.web.exposure.include", exposedEndpoints));
    }

    @Test
    void defaultsAreSafeForProduction() {
        assertThat(ProductionSafetyCheck.findings(new PayoutProperties(), new String[0], SAFE_ENDPOINTS)).isEmpty();
    }

    @Test
    void reportsEveryDevelopmentOnlySetting() {
        PayoutProperties properties = new PayoutProperties();
        properties.getLedger().setSeedEnabled(true);
        properties.getFunding().setRequireApproval(false);
        properties.getRateLimit().setEnabled(false);
        properties.getLedger().setFailOnUnmigratedLegacyData(false);

        assertThat(ProductionSafetyCheck.findings(properties, new String[]{"dev"}, DEV_ENDPOINTS)).hasSize(6);
        assertThat(ProductionSafetyCheck.developmentFeatures(properties, new String[]{"dev"}, DEV_ENDPOINTS)).hasSize(3);
    }

    // --- permitted configurations -------------------------------------------------------------

    @Test
    void localDevelopmentWithExplicitLocalEnvironmentIsPermitted() {
        assertThatCode(() -> devSetup("local").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> devSetup(" LOCAL ").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void testEnvironmentMayEnableSeeding() {
        PayoutProperties properties = new PayoutProperties();
        properties.setEnvironment("test");
        properties.getLedger().setSeedEnabled(true);

        assertThatCode(() -> check(properties, SAFE_ENDPOINTS).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void productionWithoutDevelopmentSettingsStarts() {
        PayoutProperties properties = new PayoutProperties();
        properties.setEnvironment("production");

        assertThatCode(() -> check(properties, SAFE_ENDPOINTS).afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void safetyReductionsOutsideProductionAreOnlyWarnings() {
        PayoutProperties properties = new PayoutProperties();
        properties.setEnvironment("staging");
        properties.getFunding().setRequireApproval(false);

        assertThatCode(() -> check(properties, SAFE_ENDPOINTS).afterPropertiesSet()).doesNotThrowAnyException();
    }

    // --- refused configurations ---------------------------------------------------------------

    @Test
    void devProfileWithoutAnExplicitEnvironmentIsRefused() {
        assertThatThrownBy(() -> devSetup("").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_ENVIRONMENT=local")
                .hasMessageContaining("not set")
                .hasMessageContaining("the dev profile is active");
        assertThatThrownBy(() -> devSetup(null).afterPropertiesSet())
                .hasMessageContaining("APP_ENVIRONMENT=local");
    }

    @Test
    void devProfileInAnyOtherEnvironmentIsRefused() {
        assertThatThrownBy(() -> devSetup("staging").afterPropertiesSet())
                .hasMessageContaining("APP_ENVIRONMENT=local")
                .hasMessageContaining("staging");
    }

    @Test
    void productionRefusesDevelopmentAndSafetyReducingSettings() {
        assertThatThrownBy(() -> devSetup("production").afterPropertiesSet())
                .hasMessageContaining("Unsafe configuration for production")
                .hasMessageContaining("the dev profile is active");

        PayoutProperties properties = new PayoutProperties();
        properties.setEnvironment("production");
        properties.getRateLimit().setEnabled(false);
        assertThatThrownBy(() -> check(properties, SAFE_ENDPOINTS).afterPropertiesSet())
                .hasMessageContaining("rate limiting is disabled");
    }

    @Test
    void seedingOrSensitiveEndpointsWithoutTheDevProfileAreStillRefusedOutsideLocal() {
        PayoutProperties seeding = new PayoutProperties();
        seeding.getLedger().setSeedEnabled(true);
        assertThatThrownBy(() -> check(seeding, SAFE_ENDPOINTS).afterPropertiesSet())
                .hasMessageContaining("ledger seeding is enabled");

        assertThatThrownBy(() -> check(new PayoutProperties(), DEV_ENDPOINTS).afterPropertiesSet())
                .hasMessageContaining("sensitive actuator endpoints");
        assertThatThrownBy(() -> check(new PayoutProperties(), "*").afterPropertiesSet())
                .hasMessageContaining("sensitive actuator endpoints");
    }
}
