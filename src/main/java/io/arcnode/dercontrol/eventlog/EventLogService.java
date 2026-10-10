package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DispatchMode;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;

/** Writes the site's event log from the code paths that make things happen. */
@Service
public class EventLogService {

  /** The broker role and HMI role an operator acts under; v1 has no per-person identity. */
  private static final String OPERATOR = "operator";

  private final EventLogRepository repository;
  private final Clock clock;

  public EventLogService(EventLogRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  /**
   * The rows a query selects, in the order it asks for.
   *
   * @param query bounds, filters, cursor and cap
   * @return the matching rows, oldest first for a tail, newest first behind a cursor
   */
  public List<EventLog> query(EventLogQuery query) {
    return repository.findBy(
        query.specification(), q -> q.sortBy(query.sort()).limit(query.limit()).all());
  }

  /**
   * A DERControl arrived under an mRID this site had not seen; {@code actor} is the sender's LFDI.
   */
  public void derEventReceived(DerEvent event, String actor) {
    repository.save(fromUtility(EventType.DER_EVENT_RECEIVED, event, actor));
  }

  /** The utility re-sent a known mRID; {@code actor} is the sender's LFDI. */
  public void derEventUpdated(DerEvent event, String actor) {
    repository.save(fromUtility(EventType.DER_EVENT_UPDATED, event, actor));
  }

  /** An operator approved or rejected a pending event. */
  public void derEventDecided(DerEvent event, boolean approved) {
    EventType type = approved ? EventType.DER_EVENT_APPROVED : EventType.DER_EVENT_REJECTED;
    repository.save(fromUtility(type, event, OPERATOR));
  }

  /** An operator set the energy held back from answering the envelope, in watt-hours. */
  public void operatorReserveSet(double reserveWh) {
    repository.save(
        new EventLog(
            clock.instant(),
            EventType.OPERATOR_RESERVE_SET,
            "operator_reserve",
            OPERATOR,
            null,
            null,
            null,
            reserveWh,
            null));
  }

  /** An operator switched the site's dispatch policy. */
  public void dispatchModeSet(DispatchMode mode) {
    repository.save(
        new EventLog(
            clock.instant(),
            EventType.DISPATCH_MODE_SET,
            "dispatch_mode",
            OPERATOR,
            null,
            null,
            null,
            null,
            mode.name()));
  }

  /**
   * Records that the site's resolved posture for an event is now {@code state}, if that is news.
   *
   * @param event the event whose posture was just resolved
   * @param state the posture resolved for it
   */
  public void derEventState(DerEvent event, DerEventState state) {
    // Reason: the posture is restated on boot, on broker reconnect and at every interval edge,
    // and only the first statement of a new posture is an event. The last row is the memory.
    boolean unchanged =
        repository
            .findFirstBySubjectAndTypeOrderByOccurredAtDesc(
                event.getMrid(), EventType.DER_EVENT_STATE)
            .map(last -> last.getState() == state)
            .orElse(false);
    if (unchanged) {
      return;
    }
    repository.save(
        new EventLog(
            clock.instant(),
            EventType.DER_EVENT_STATE,
            event.getMrid(),
            null,
            event.getProgram(),
            event.getStatus(),
            state,
            null,
            null));
  }

  /** A row about one event as the utility sent it: who, which program, what status. */
  private EventLog fromUtility(EventType type, DerEvent event, String actor) {
    return new EventLog(
        clock.instant(),
        type,
        event.getMrid(),
        actor,
        event.getProgram(),
        event.getStatus(),
        null,
        null,
        null);
  }
}
