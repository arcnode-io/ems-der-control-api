package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Business logic for the DERControl ingest resource. */
@Service
public class DerEventService {

  private static final Logger LOG = LoggerFactory.getLogger(DerEventService.class);

  /** Utility statuses that are not terminal — a closed event drops out of der_dispatch's field. */
  private static final List<DerControlStatus> OPEN_STATUSES =
      List.of(DerControlStatus.SCHEDULED, DerControlStatus.ACTIVE);

  private final DerEventRepository repository;
  private final DispatchPublisher publisher;
  private final EnvelopeFeedMonitor envelopeFeedMonitor;
  private final Clock clock;
  private final TaskScheduler scheduler;

  public DerEventService(
      DerEventRepository repository,
      DispatchPublisher publisher,
      EnvelopeFeedMonitor envelopeFeedMonitor,
      Clock clock,
      TaskScheduler scheduler) {
    this.repository = repository;
    this.publisher = publisher;
    this.envelopeFeedMonitor = envelopeFeedMonitor;
    this.clock = clock;
    this.scheduler = scheduler;
  }

  /**
   * Validate → persist → publish immediately (so PENDING/ARMED is visible right away) → arm a
   * re-publish at {@code interval.start} if it hasn't opened yet, so ARMED flips to ACTIVE the
   * moment it does without requiring anything else to happen. A re-transmitted mRID (status change,
   * cancellation) updates the existing row rather than duplicating it — 2030.5 servers re-send the
   * same event on every state change, keyed by mRID.
   *
   * @param clientCertHeader the gateway-forwarded {@code X-SSL-Client-Cert} header (URL-encoded
   *     PEM) — identity of the utility/aggregator that sent this event, recorded for audit
   */
  @Transactional
  public DerEventResponse ingest(
      DerControlRequest request, String receivedDocument, String clientCertHeader) {
    String lfdi = ClientIdentity.fromHeaderValue(clientCertHeader).lfdi();
    DerEvent event =
        repository
            .findByMrid(request.mrid())
            .map(existing -> apply(existing, request, lfdi))
            .orElseGet(() -> fromRequest(request, receivedDocument, lfdi));

    DerEvent saved = repository.save(event);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "✅ Complying with DER dispatch: mrid={}, status={}, target={}W, energize={}",
          saved.getMrid(),
          saved.getStatus(),
          saved.getTargetActivePowerW(),
          saved.getEnergize());
    }
    publishGoverning(saved);
    // Reason: only the envelope schedule says anything about whether the envelope is still
    // arriving.
    // The envelope carries the interval it is valid for, so the monitor needs no cadence constant.
    if (saved.isEnvelopeOnly()) {
      envelopeFeedMonitor.recordEnvelope(
          saved.getIntervalStart().plusSeconds(saved.getDurationSeconds()));
    }
    armFutureRepublish(saved);
    armRelease(saved);
    return DerEventResponse.from(saved);
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

  private void armFutureRepublish(DerEvent event) {
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
  private void armRelease(DerEvent event) {
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
   * Publishes whichever event {@code der_dispatch} should reflect right now, not the one that
   * happened to change. An envelope refresh carries only limits and never touches der_dispatch, so
   * it publishes itself.
   */
  private void publishGoverning(DerEvent changed) {
    publisher.publish(changed.isEnvelopeOnly() ? changed : governing(changed));
  }

  /**
   * The curtailment governing the site. {@code der_dispatch} is site-level, not a channel per mRID,
   * so closing one event must not release the site while another is still in force — those channels
   * carry a setpoint written straight through to plant. Most recently received still-open
   * curtailment wins, since 2030.5 supersession is a later event replacing an earlier one.
   *
   * @param changed the event that just changed — the answer when nothing else is still open
   */
  private DerEvent governing(DerEvent changed) {
    return openGoverning().orElse(changed);
  }

  /** The still-open curtailment in force, or empty when the site is uncommanded. */
  private Optional<DerEvent> openGoverning() {
    return repository.findByStatusIn(OPEN_STATUSES).stream()
        .filter(candidate -> !candidate.isEnvelopeOnly())
        .max(Comparator.comparing(DerEvent::getReceivedAt));
  }

  /**
   * Applies an operator's {@code approve_dispatch} command. Commands carry no mRID (the fixed
   * {@code commands/{verb}/event_active/none} topic shape has no slot for one) unless the sender
   * chose to include one in the payload — when present, targets that exact event; a doubled or
   * unresolvable command is a no-op, not an error, so it shouldn't crash the MQTT subscriber.
   *
   * @param mrid the event to target, or {@code null} to fall back to the nearest still-undecided
   *     event's own {@code interval.start}
   */
  @Transactional
  public void approveCurrentPending(@Nullable String mrid) {
    decideCurrentPending(mrid, true);
  }

  /** Applies an operator's {@code reject_dispatch} command — see {@link #approveCurrentPending}. */
  @Transactional
  public void rejectCurrentPending(@Nullable String mrid) {
    decideCurrentPending(mrid, false);
  }

  private void decideCurrentPending(@Nullable String mrid, boolean approved) {
    Optional<DerEvent> target =
        mrid != null
            ? repository.findByMrid(mrid)
            : repository.findFirstByApprovedIsNullOrderByIntervalStartAsc();
    target.ifPresent(
        event -> {
          event.setApproved(approved);
          DerEvent saved = repository.save(event);
          publishGoverning(saved);
        });
  }

  public Optional<DerEventResponse> findByMrid(String mrid) {
    return repository.findByMrid(mrid).map(DerEventResponse::from);
  }

  public List<DerEventResponse> findByStatus(DerControlStatus status) {
    return repository.findByStatus(status).stream().map(DerEventResponse::from).toList();
  }

  private DerEvent fromRequest(DerControlRequest request, String receivedDocument, String lfdi) {
    return new DerEvent(
        request.mrid(),
        request.eventStatus(),
        request.interval().start(),
        request.interval().durationSeconds(),
        request.derControlBase().opModTargetW(),
        request.derControlBase().opModEnergize(),
        request.derControlBase().opModImpLimW(),
        request.derControlBase().opModExpLimW(),
        receivedDocument,
        lfdi);
  }

  private DerEvent apply(DerEvent existing, DerControlRequest request, String lfdi) {
    existing.setStatus(request.eventStatus());
    existing.setIntervalStart(request.interval().start());
    existing.setDurationSeconds(request.interval().durationSeconds());
    existing.setTargetActivePowerW(request.derControlBase().opModTargetW());
    existing.setEnergize(request.derControlBase().opModEnergize());
    existing.setImportLimitW(request.derControlBase().opModImpLimW());
    existing.setExportLimitW(request.derControlBase().opModExpLimW());
    existing.setSubmittedByLfdi(lfdi);
    return existing;
  }
}
