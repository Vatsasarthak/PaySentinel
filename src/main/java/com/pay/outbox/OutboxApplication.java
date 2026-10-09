package com.pay.outbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;

import java.util.TimeZone;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableRetry
public class OutboxApplication {

	public static void main(String[] args) {
		useUtcAsDefaultTimeZone();
		SpringApplication.run(OutboxApplication.class, args);
	}

	/**
	 * Timestamps are stored as TIMESTAMPTZ and handled as Instants, so the JVM zone does not affect
	 * stored values. The PostgreSQL driver, however, sends the JVM's zone ID when connecting, and
	 * legacy aliases (e.g. "Asia/Calcutta" on Windows) are rejected by servers built without
	 * legacy tzdata. A fixed UTC default makes connections independent of the host's settings.
	 */
	public static void useUtcAsDefaultTimeZone() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

}
