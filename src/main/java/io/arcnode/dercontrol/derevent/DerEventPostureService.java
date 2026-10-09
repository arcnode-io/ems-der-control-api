package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * What {@code der_dispatch} says about the site's events, and when. Publishes the event that
 * governs the site rather than the one that changed, restates that posture on boot and reconnect,
 * and re-publishes by itself when an interval opens or ends. Storing and deciding events is {@link
 * DerEventService}'s.
 */
@Service
public class DerEventPostureService {

  /** Utility statuses that are not terminal — a closed event drops out of der_dispatch's field. */
  private static final List<DerControlStatus> OPEN_STATUSES =
      List.of(DerControlStatus.SCHEDULED, DerControlStatus.ACTIVE);

  private final DerEventRepository repository;
  private final DispatchPublisher publisher;
  private final Clock clock;
  private final TaskScheduler scheduler;

  public DerEventPostureService(
      DerEventRepository repository,
      DispatchPublisher publisher,
      Clock clock,
      TaskScheduler scheduler) {
    this.repository = repository;
    this.publisher = publisher;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  /**
   * Re-arms every persisted event whose {@code interval.start} hasn't opened yet, and the release
   * of every still-open event whose interval hasn't ended yet. Spring's {@code TaskScheduler} is
   * in-memory only — a restart loses both scheduled tasks entirely, so this recovers them on boot.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void rearmFutureEvents() {
    repository.findByIntervalStartAfter(clock.instant()).forEach(this::armFutureRepublish);
    repository.findByStatusIn(OPEN_STATUSES).forEach(this::armRelease);
  }

  /**
   * States what {@code der_dispatch} should reflect right now, with no event having changed.
   *
   * <p>Called whenever this service's retained state may be gone: its own boot, and a broker
   * restart, which drops every retained message while this process stays up none the wiser.
   *
   * <p>Reason for consulting the store rather than simply declaring the site idle: a restart in the
   * middle of a curtailment would otherwise publish event_active false, which tells a consumer
   * gating on it that the plant is free — on the charge path, importing power during the very event
   * that asked the site to back off.
   */
  public void statePosture() {
    openGoverning().ifPresentOrElse(publisher::publish, publisher::publishIdlePosture);
  }

  /**
   * Publishes whichever event {@code der_dispatch} should reflect right now, not the one that
   * happened to change. An envelope refresh carries only limits and never touches der_dispatch, so
   * it publishes itself.
   */
  public void publishGoverning(DerEvent changed) {
    publisher.publish(changed.isEnvelopeOnly() ? changed : governing(changed));
  }

  /** Publishes again at {@code interval.start}, so ARMED flips to ACTIVE the moment it opens. */
  public void armFutureRepublish(DerEvent event) {
    Instant start = event.getIntervalStart();
    if (start.isAfter(clock.instant())) {
      scheduler.schedule(() -> publishGoverning(event), start);
    }
  }

  /**
   * Publishes again the moment this event's interval ends.
   *
   * <p>Reason: the interval is the authority on when an event is over, and der_dispatch is
   * retained. Without this, nothing republishes at the end and the last setpoint stands — a
   * consumer keeps executing an expired dispatch until the utility happens to send a terminal
   * status. A DERMS that crashes or partitions would leave storage discharging to its floor.
   * Confirmed live before fixing: 24s past a 120s event's end, the pack was still at the commanded
   * 1.12 MW on a retained event_active stamped at dispatch time.
   *
   * <p>Needs no release-specific publish: {@code derEventState(mode, now)} already resolves an
   * expired event to IDLE, so the ordinary publish path states the released posture by itself.
   */
  public void armRelease(DerEvent event) {
    // Reason: an envelope never publishes der_dispatch at all, so it has nothing to release. It
    // also re-POSTs continuously under one mRID with a fresh short interval, so arming it would
    // queue a task every few seconds for the life of the process.
    if (event.isEnvelopeOnly()) {
      return;
    }
    Instant end = event.getIntervalStart().plusSeconds(event.getDurationSeconds());
    if (end.isAfter(clock.instant())) {
      // Reason: statePosture re-reads the store when it fires, rather than this method capturing
      // the entity. A captured entity keeps the status it had when armed, so an event the utility
      // closed early would be republished as still in force at its original interval end —
      // re-asserting a curtailment that had already ended, which is worse than never releasing.
      scheduler.schedule(this::statePosture, end);
    }
  }

  /**
   * The curtailment governing the site. {@code der_dispatch} is site-level, not a channel per mRID,
   * so closing one event must not release the site while another is still in force — those channels
   * carry a setpoint written straight through to plant.
   *
   * @param changed the event that just changed — the answer when nothing else is still open
   */
  private DerEvent governing(DerEvent changed) {
    return openGoverning().orElse(changed);
  }

  /**
   * The still-open curtailment in force, or empty when the site is uncommanded.
   *
   * <p>Reason for the order: IEEE 2030.5 ranks overlapping controls by their program's primacy
   * first (lower wins) and only then by which arrived later. Arrival order alone would let a
   * conductor limit be masked by a later contracted call, or the reverse, purely by timing.
   */
  private Optional<DerEvent> openGoverning() {
    return repository.findByStatusIn(OPEN_STATUSES).stream()
        .filter(candidate -> !candidate.isEnvelopeOnly())
        .min(
            Comparator.comparingInt((DerEvent candidate) -> candidate.getProgram().primacy())
                .thenComparing(DerEvent::getReceivedAt, Comparator.reverseOrder()));
  }
}
