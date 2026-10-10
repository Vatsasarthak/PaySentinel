package com.pay.outbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Tunables for the payout pipeline. Scheduling intervals are bound directly in the
 * {@code @Scheduled} annotations through {@code payout.*.interval-ms} placeholders.
 */
@Data
@ConfigurationProperties(prefix = "payout")
public class PayoutProperties {

    /** ISO 4217 codes accepted by the API. The ledger is single-currency per account. */
    private List<String> supportedCurrencies = List.of("INR");

    /**
     * Deployment environment (APP_ENVIRONMENT). Development-only features require it to be explicitly
     * {@code local}; {@code production} refuses every unsafe setting. Unset by default, so nothing is
     * treated as a developer machine unless declared (see ProductionSafetyCheck).
     */
    private String environment = "";

    private final Relay relay = new Relay();
    private final Worker worker = new Worker();
    private final Queue queue = new Queue();
    private final Recovery recovery = new Recovery();
    private final Gateway gateway = new Gateway();
    private final Ledger ledger = new Ledger();
    private final Security security = new Security();
    private final RateLimit rateLimit = new RateLimit();
    private final Funding funding = new Funding();

    @Data
    public static class Relay {
        /** Maximum outbox events published per relay run. */
        private int batchSize = 100;
    }

    @Data
    public static class Worker {
        /** Maximum queue messages claimed per dispatch round. */
        private int batchSize = 20;
        /** Threads processing claimed messages in parallel. */
        private int concurrency = 16;
        /** How long a claimed payout is reserved for one worker before another may take over. */
        private Duration leaseDuration = Duration.ofSeconds(60);
        /** Claims allowed per payout before it is failed and dead-lettered. */
        private int maxDeliveryAttempts = 5;
    }

    @Data
    public static class Queue {
        /** Age after which a message in the processing list is considered abandoned and re-queued. */
        private Duration visibilityTimeout = Duration.ofMinutes(2);
        /** Maximum processing-list entries inspected per recovery run. */
        private int recoveryScanLimit = 500;
    }

    @Data
    public static class Recovery {
        /** RESERVING payouts older than this are cancelled and their holds released. */
        private Duration reservationTimeout = Duration.ofMinutes(1);
        /** PENDING/PROCESSING payouts idle for longer than this are re-published if their message is gone. */
        private Duration stuckPayoutTimeout = Duration.ofMinutes(10);
        private int batchSize = 100;
    }

    @Data
    public static class Gateway {
        /** Simulated outcome distribution; the remainder (100 - success - failure) are hangs. */
        private int successPercent = 70;
        private int failurePercent = 20;
        /** Share of timeouts where the provider had already executed the payment (response lost). */
        private int processedBeforeTimeoutPercent = 50;
    }

    @Data
    public static class Ledger {
        /** Enables POST /api/v1/payouts/ledger/seed. Development and testing only. */
        private boolean seedEnabled = false;
        /**
         * Imports balances and holds from the pre-V4 Redis ledger at startup (idempotent; imported
         * Redis keys are renamed to ledger-migrated:*, never deleted).
         */
        private boolean redisImportEnabled = true;
        /**
         * Refuse to start while legacy Redis ledger data remains unimported (import conflicts, or the
         * import disabled with legacy data present): such payouts could not be processed safely.
         */
        private boolean failOnUnmigratedLegacyData = true;
    }

    @Data
    public static class Funding {
        /**
         * Dual control: an adjustment is posted only after a second, different treasury key approves it.
         * When false, adjustments post immediately (single-operator development setups only).
         */
        private boolean requireApproval = true;
    }

    @Data
    public static class Security {
        /** API clients and the ledger accounts each one may use. */
        private List<Client> clients = new ArrayList<>();
        private Admin admin = new Admin();
        /** Treasury keys: funding adjustments and manual-review resolutions. None by default. */
        private List<Treasurer> treasury = new ArrayList<>();
    }

    @Data
    public static class Treasurer {
        private String name;
        private String apiKey;
        private String apiKeySha256;
        /** Accounts this key may fund or debit and whose payout reviews it may resolve. */
        private List<String> accounts = new ArrayList<>();
        /** Explicit opt-in to act on every account. */
        private boolean allAccounts = false;
    }

    @Data
    public static class Client {
        private String name;
        /** Plain API key, or {@link #apiKeySha256} (hex SHA-256 of the key) so the server never stores it. */
        private String apiKey;
        private String apiKeySha256;
        /** Accounts this client may create payouts for and read payouts of. Nothing is implied. */
        private List<String> accounts = new ArrayList<>();
    }

    @Data
    public static class Admin {
        private String apiKey;
        private String apiKeySha256;
        /** Accounts the admin key may create payouts for. Empty by default: ADMIN is an operations role. */
        private List<String> accounts = new ArrayList<>();
        /** Lets the admin key read every payout (support use). Off by default. */
        private boolean readAllPayouts = false;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        /** Allow requests when the limiter itself fails (Redis down). False rejects them with 503. */
        private boolean failOpen = true;
        /** POST /api/v1/payouts, per API client. */
        private Policy create = new Policy(50, 20);
        /** Payout reads, per API client. */
        private Policy read = new Policy(200, 100);
        /** Ledger, dead-letter and protected actuator endpoints, per API client. */
        private Policy admin = new Policy(30, 10);
        /** Requests with an invalid API key, per client IP address. */
        private Policy authFailure = new Policy(20, 1);
    }

    /** Token bucket: up to {@code capacity} requests in a burst, refilled at {@code refillPerSecond}. */
    @Data
    public static class Policy {
        private int capacity;
        private double refillPerSecond;

        public Policy() {
        }

        public Policy(int capacity, double refillPerSecond) {
            this.capacity = capacity;
            this.refillPerSecond = refillPerSecond;
        }
    }
}
