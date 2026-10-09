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
  void aFreshDatabaseIsBuiltByTheBaselineMigration() {
    // Arrange: the context booted against an empty Postgres

    // Act
    List<Map<String, Object>> applied =
        jdbc.queryForList(
            "select version, success from der_control_schema_history order by installed_rank");

    // Assert
    assertThat(applied).hasSize(1);
    assertThat(applied.get(0)).containsEntry("version", "1").containsEntry("success", true);
  }
}
