package com.pay.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxApplicationTests extends BaseIntegrationTest {

	@Autowired
	private RedisConnectionFactory redisConnectionFactory;

	@Autowired
	private DataSource dataSource;

	@Test
	void contextLoadsAndAppliesAllMigrations() {
		Integer applied = jdbcTemplate.queryForObject(
				"select count(*) from flyway_schema_history where success", Integer.class);
		assertThat(applied).isEqualTo(5);
	}

	@Test
	void redisConnectsToTheTestcontainerNotALocalInstance() throws Exception {
		LettuceConnectionFactory lettuce = (LettuceConnectionFactory) redisConnectionFactory;
		assertThat(lettuce.getHostName()).isEqualTo(redis.getHost());
		assertThat(lettuce.getPort()).isEqualTo(redis.getMappedPort(6379));

		redisTemplate.opsForValue().set("container-check", "ok");
		assertThat(redis.execInContainer("redis-cli", "GET", "container-check").getStdout().trim())
				.isEqualTo("ok");
	}

	@Test
	void postgresConnectsToTheTestcontainer() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getURL()).isEqualTo(postgres.getJdbcUrl());
		}
	}
}
