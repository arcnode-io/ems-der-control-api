package io.arcnode.dercontrol.eventlog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only access to the site's event log. */
public interface EventLogRepository extends JpaRepository<EventLog, Long> {

  /** The newest row of one type for one subject — how a state recorder knows what changed. */
  Optional<EventLog> findFirstBySubjectAndTypeOrderByOccurredAtDesc(String subject, EventType type);

  /** Everything after an instant, oldest first, capped — the HMI's history read. */
  List<EventLog> findByOccurredAtAfterOrderByOccurredAtAsc(Instant since, Limit limit);
}
