package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.Config;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the event log to the configured window: a daily purge of rows older than it. */
@Component
public class EventLogRetention {

  private static final Logger LOG = LoggerFactory.getLogger(EventLogRetention.class);
  private static final long DAILY_MILLIS = 24L * 60 * 60 * 1000;

  private final EventLogRepository repository;
  private final Clock clock;
  private final Config config;

  public EventLogRetention(EventLogRepository repository, Clock clock, Config config) {
    this.repository = repository;
    this.clock = clock;
    this.config = config;
  }

  /** Deletes every row that fell out of the retention window. */
  @Scheduled(fixedDelay = DAILY_MILLIS)
  @Transactional
  public void purge() {
    Instant cutoff = clock.instant().minus(Duration.ofDays(config.eventRetentionDays()));
    long gone = repository.deleteByOccurredAtBefore(cutoff);
    if (gone > 0 && LOG.isInfoEnabled()) {
      LOG.info("🧹 event log: purged {} rows older than {}", gone, cutoff);
    }
  }
}
