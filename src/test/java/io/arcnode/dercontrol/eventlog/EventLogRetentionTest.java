package io.arcnode.dercontrol.eventlog;

import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — the purge cuts at exactly now minus the configured window. AAA. */
@ExtendWith(MockitoExtension.class)
class EventLogRetentionTest {

  private static final Instant NOW = Instant.parse("2026-10-10T19:00:00Z");

  @Mock private EventLogRepository repository;

  private static Config withRetentionDays(int days) {
    return new Config(
        Config.LogLevel.INFO,
        8080,
        "localhost",
        false,
        "localhost",
        "tcp://localhost:1883",
        "u",
        "local_site",
        "http://localhost:8081",
        "http://localhost:8080",
        days);
  }

  @Test
  void purgeDeletesEverythingOlderThanTheRetentionWindow() {
    // Arrange: 90 days, so the cutoff is 2026-07-12T19:00Z
    EventLogRetention retention =
        new EventLogRetention(repository, Clock.fixed(NOW, ZoneOffset.UTC), withRetentionDays(90));

    // Act
    retention.purge();

    // Assert
    verify(repository).deleteByOccurredAtBefore(Instant.parse("2026-07-12T19:00:00Z"));
  }
}
