package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Business logic for the DERControl ingest resource. */
@Service
public class DerEventService {

  private final DerEventRepository repository;
  private final DispatchPublisher publisher;
  private final JsonMapper mapper;
  private final Clock clock;
  private final TaskScheduler scheduler;

  public DerEventService(
      DerEventRepository repository,
      DispatchPublisher publisher,
      JsonMapper mapper,
      Clock clock,
      TaskScheduler scheduler) {
    this.repository = repository;
    this.publisher = publisher;
    this.mapper = mapper;
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
  public DerEventResponse ingest(DerControlRequest request, String clientCertHeader) {
    String lfdi = ClientIdentity.fromHeaderValue(clientCertHeader).lfdi();
    DerEvent event =
        repository
            .findByMrid(request.mrid())
            .map(existing -> apply(existing, request, lfdi))
            .orElseGet(() -> fromRequest(request, lfdi));

    DerEvent saved = repository.save(event);
    publisher.publish(saved);
    armFutureRepublish(saved);
    return DerEventResponse.from(saved);
  }

  /**
   * Re-arms every persisted event whose {@code interval.start} hasn't opened yet. Spring's {@code
   * TaskScheduler} is in-memory only — a restart between {@link #ingest} arming a republish and
   * that interval actually opening loses the scheduled task entirely, so this recovers it on boot.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void rearmFutureEvents() {
    repository.findByIntervalStartAfter(clock.instant()).forEach(this::armFutureRepublish);
  }

  private void armFutureRepublish(DerEvent event) {
    Instant start = event.getIntervalStart();
    if (start.isAfter(clock.instant())) {
      scheduler.schedule(() -> publisher.publish(event), start);
    }
  }

  public Optional<DerEventResponse> findByMrid(String mrid) {
    return repository.findByMrid(mrid).map(DerEventResponse::from);
  }

  public List<DerEventResponse> findByStatus(DerControlStatus status) {
    return repository.findByStatus(status).stream().map(DerEventResponse::from).toList();
  }

  private DerEvent fromRequest(DerControlRequest request, String lfdi) {
    return new DerEvent(
        request.mrid(),
        request.eventStatus(),
        request.interval().start(),
        request.interval().durationSeconds(),
        request.derControlBase().opModTargetW(),
        request.derControlBase().opModEnergize(),
        request.derControlBase().opModImpLimW(),
        request.derControlBase().opModExpLimW(),
        mapper.writeValueAsString(request),
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
