package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The schema comes from versioned migrations, not from Hibernate guessing at it. Booting at all is
 * the other half of this test: Hibernate runs in {@code validate} mode, so a migration that drifts
 * from the entities fails the context before any test runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SchemaMigrationIT extends AbstractBrokerIT {

  @Autowired JdbcTemplate jdbc;

  @Test
  void aFreshDatabaseIsBuiltByEveryMigrationInOrder() {
    // Arrange: the context booted against an empty Postgres

    // Act
    List<Map<String, Object>> applied =
        jdbc.queryForList(
            "select version, success from der_control_schema_history order by installed_rank");

    // Assert
    assertThat(applied).extracting("version").containsExactly("1", "2");
    assertThat(applied).extracting("success").containsOnly(true);
  }

  @Test
  void theEventLogStartsEmpty() {
    // Arrange: V2 created event_log and nothing has happened on this site yet

    // Act
    Integer rows = jdbc.queryForObject("select count(*) from event_log", Integer.class);

    // Assert
    assertThat(rows).isZero();
  }
}
