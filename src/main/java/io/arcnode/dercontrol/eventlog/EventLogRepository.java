package io.arcnode.dercontrol.eventlog;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Append-only access to the site's event log. */
public interface EventLogRepository
    extends JpaRepository<EventLog, Long>, JpaSpecificationExecutor<EventLog> {

  /** The newest row of one type for one subject — how a state recorder knows what changed. */
  Optional<EventLog> findFirstBySubjectAndTypeOrderByOccurredAtDesc(String subject, EventType type);

  /**
   * Drops every row older than the cutoff — the retention purge.
   *
   * @return how many rows went
   */
  long deleteByOccurredAtBefore(Instant cutoff);
}
