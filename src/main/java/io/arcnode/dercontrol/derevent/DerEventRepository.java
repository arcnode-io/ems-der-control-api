package io.arcnode.dercontrol.derevent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link DerEvent}. */
public interface DerEventRepository extends JpaRepository<DerEvent, Long> {

  Optional<DerEvent> findByMrid(String mrid);

  List<DerEvent> findByStatus(DerControlStatus status);

  /**
   * Events not yet at their interval.start — for re-arming a scheduled republish after a restart.
   */
  List<DerEvent> findByIntervalStartAfter(Instant now);

  /**
   * The still-undecided event nearest its own interval.start — the approve/reject fallback when a
   * command carries no mRID (the fixed commands/{verb}/event_active/none topic shape has no slot
   * for one).
   */
  Optional<DerEvent> findFirstByApprovedIsNullOrderByIntervalStartAsc();
}
