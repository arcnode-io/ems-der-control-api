package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for the DERControl ingest resource: stores events, applies an operator's decision,
 * and answers lookups. What the bus is told, and when, is {@link DerEventPostureService}'s.
 */
@Service
public class DerEventService {

  private static final Logger LOG = LoggerFactory.getLogger(DerEventService.class);

  private final DerEventRepository repository;
  private final DerEventPostureService posture;
  private final EnvelopeFeedMonitor envelopeFeedMonitor;
  private final EventLogService eventLog;

  public DerEventService(
      DerEventRepository repository,
      DerEventPostureService posture,
      EnvelopeFeedMonitor envelopeFeedMonitor,
      EventLogService eventLog) {
    this.repository = repository;
    this.posture = posture;
    this.envelopeFeedMonitor = envelopeFeedMonitor;
    this.eventLog = eventLog;
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
    Optional<DerEvent> known = repository.findByMrid(request.mrid());
    DerEvent event =
        known
            .map(existing -> apply(existing, request, lfdi))
            .orElseGet(() -> fromRequest(request, receivedDocument, lfdi));

    DerEvent saved = repository.save(event);
    // Reason: same transaction as the row it describes, so the log never says something the
    // store does not.
    if (known.isPresent()) {
      eventLog.derEventUpdated(saved, lfdi);
    } else {
      eventLog.derEventReceived(saved, lfdi);
    }
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "✅ Complying with DER dispatch: mrid={}, status={}, target={}W, energize={}",
          saved.getMrid(),
          saved.getStatus(),
          saved.getControl().targetActivePowerW(),
          saved.getControl().energize());
    }
    posture.publishGoverning(saved);
    // Reason: only the envelope schedule says anything about whether the envelope is still
    // arriving. The envelope carries the interval it is valid for, so the monitor needs no cadence
    // constant.
    if (saved.isEnvelopeOnly()) {
      envelopeFeedMonitor.recordEnvelope(
          saved.getIntervalStart().plusSeconds(saved.getDurationSeconds()));
    }
    posture.armFutureRepublish(saved);
    posture.armRelease(saved);
    return DerEventResponse.from(saved);
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
          eventLog.derEventDecided(saved, approved);
          posture.publishGoverning(saved);
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
        lfdi,
        request.program());
  }

  private DerEvent apply(DerEvent existing, DerControlRequest request, String lfdi) {
    existing.setStatus(request.eventStatus());
    existing.setIntervalStart(request.interval().start());
    existing.setDurationSeconds(request.interval().durationSeconds());
    existing.setControl(
        new DerControlBase(
            request.derControlBase().opModTargetW(),
            request.derControlBase().opModEnergize(),
            request.derControlBase().opModImpLimW(),
            request.derControlBase().opModExpLimW()));
    existing.setSubmittedByLfdi(lfdi);
    return existing;
  }
}
