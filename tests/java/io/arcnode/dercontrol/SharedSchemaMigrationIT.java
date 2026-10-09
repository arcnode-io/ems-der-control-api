package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The deployment's Postgres schema is shared with device-api, and on a new box device-api may boot
 * first. A schema that already holds someone else's tables must still end up with ours: Flyway's
 * "non-empty schema" baseline must not skip the migration that creates them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SharedSchemaMigrationIT extends AbstractBrokerIT {

  // Reason: not the shared @ServiceConnection container — that one is empty when the first context
  // boots. This one already belongs to another service by the time we arrive.
  static final PostgreSQLContainer OCCUPIED = new PostgreSQLContainer("postgres:16-alpine");

  static {
    if (DockerClientFactory.instance().isDockerAvailable()) {
      OCCUPIED.start();
      try (Connection c =
              DriverManager.getConnection(
                  OCCUPIED.getJdbcUrl(), OCCUPIED.getUsername(), OCCUPIED.getPassword());
          Statement s = c.createStatement()) {
        s.execute("create table topology (id bigint primary key, dtm text)");
      } catch (SQLException e) {
        throw new IllegalStateException("could not seed the other service's table", e);
      }
    }
  }

  @DynamicPropertySource
  static void occupiedDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", OCCUPIED::getJdbcUrl);
    registry.add("spring.datasource.username", OCCUPIED::getUsername);
    registry.add("spring.datasource.password", OCCUPIED::getPassword);
  }

  @Autowired JdbcTemplate jdbc;

  @Test
  void aSchemaAnotherServiceAlreadyLivesInStillGetsOurTables() {
    // Arrange: the context booted against a schema that already held another service's table

    // Act
    List<Map<String, Object>> applied =
        jdbc.queryForList(
            "select version, success from der_control_schema_history where version = '1'");
    Long events = jdbc.queryForObject("select count(*) from der_event", Long.class);

    // Assert
    assertThat(applied).hasSize(1);
    assertThat(applied.get(0)).containsEntry("success", true);
    assertThat(events).isZero();
  }
}
