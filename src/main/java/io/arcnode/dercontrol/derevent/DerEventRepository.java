package io.arcnode.dercontrol.derevent;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link DerEvent}. */
public interface DerEventRepository extends JpaRepository<DerEvent, Long> {

  Optional<DerEvent> findByMrid(String mrid);

  List<DerEvent> findByStatus(DerControlStatus status);

  /**
   * The event an {@code approve_dispatch}/{@code reject_dispatch} command applies to. The command
   * schema mirrors its bool target ({@code event_active}) and carries no mRID — der_dispatch is a
   * singleton per site, so "most recently ingested, still undecided" is the one event an operator
   * could mean.
   */
  Optional<DerEvent> findFirstByApprovedIsNullOrderByReceivedAtDesc();
}
