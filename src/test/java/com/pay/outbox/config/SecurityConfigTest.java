package com.pay.outbox.config;

import com.pay.outbox.security.ApiKeyAuthenticationFilter;
import com.pay.outbox.security.ApiKeyAuthenticationFilter.Credential;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startup validation of API-key configuration (no Spring context needed). */
class SecurityConfigTest {

    private static final String KEY_A = "a".repeat(40);
    private static final String KEY_B = "b".repeat(40);
    private static final String ADMIN_KEY = "c".repeat(40);

    private static PayoutProperties properties(PayoutProperties.Client... clients) {
        PayoutProperties properties = new PayoutProperties();
        properties.getSecurity().setClients(List.of(clients));
        properties.getSecurity().getAdmin().setApiKey(ADMIN_KEY);
        return properties;
    }

    private static PayoutProperties.Client client(String name, String key, String... accounts) {
        PayoutProperties.Client client = new PayoutProperties.Client();
        client.setName(name);
        client.setApiKey(key);
        client.setAccounts(List.of(accounts));
        return client;
    }

    @Test
    void buildsPrincipalsWithExplicitAccountGrantsOnly() {
        List<Credential> credentials = SecurityConfig.credentials(
                properties(client("merchant-a", KEY_A, "acct_1", " acct_2 ", "")));

        assertThat(credentials).hasSize(2);
        assertThat(credentials.get(0).principal().accounts()).containsExactlyInAnyOrder("acct_1", "acct_2");
        assertThat(credentials.get(0).principal().mayUseAccount("acct_3")).isFalse();
        assertThat(credentials.get(1).principal().role()).isEqualTo("ADMIN");
        assertThat(credentials.get(1).principal().accounts()).isEmpty();
        assertThat(credentials.get(1).principal().readAllPayouts()).isFalse();
    }

    @Test
    void acceptsSha256DigestsInsteadOfPlainKeys() {
        PayoutProperties.Client client = client("merchant-a", null, "acct_1");
        client.setApiKeySha256(HexFormat.of().formatHex(ApiKeyAuthenticationFilter.sha256(KEY_A)));

        List<Credential> credentials = SecurityConfig.credentials(properties(client));

        assertThat(credentials.get(0).keyDigest()).isEqualTo(ApiKeyAuthenticationFilter.sha256(KEY_A));
    }

    @Test
    void adminReadAllIsAnExplicitOptIn() {
        PayoutProperties properties = properties(client("merchant-a", KEY_A, "acct_1"));
        properties.getSecurity().getAdmin().setReadAllPayouts(true);

        assertThat(SecurityConfig.credentials(properties).get(1).principal().mayReadAccount("anything")).isTrue();
    }

    @Test
    void rejectsUnsafeConfigurations() {
        assertThatThrownBy(() -> SecurityConfig.credentials(properties()))
                .hasMessageContaining("At least one API client");
        assertThatThrownBy(() -> SecurityConfig.credentials(properties(client("merchant-a", "short", "a"))))
                .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> SecurityConfig.credentials(
                properties(client("merchant-a", "replace-with-at-least-32-random-characters-client", "a"))))
                .hasMessageContaining("placeholder");
        assertThatThrownBy(() -> SecurityConfig.credentials(
                properties(client("merchant-a", KEY_A, "a"), client("merchant-b", KEY_A, "b"))))
                .hasMessageContaining("different");
        assertThatThrownBy(() -> SecurityConfig.credentials(properties(client("legacy", KEY_A, "a"))))
                .hasMessageContaining("reserved");
        assertThatThrownBy(() -> SecurityConfig.credentials(
                properties(client("merchant-a", KEY_A, "a"), client("merchant-a", KEY_B, "b"))))
                .hasMessageContaining("duplicated");
        assertThatThrownBy(() -> SecurityConfig.credentials(properties(client("merchant-a", KEY_A, "bad id!"))))
                .hasMessageContaining("Invalid account id");

        PayoutProperties both = properties(client("merchant-a", KEY_A, "a"));
        both.getSecurity().getClients().get(0).setApiKeySha256("0".repeat(64));
        assertThatThrownBy(() -> SecurityConfig.credentials(both)).hasMessageContaining("exactly one");
    }
}
