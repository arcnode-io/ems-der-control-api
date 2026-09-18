package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
   * Validate → persist → publish at {@code interval.start}. A 2030.5 event with a future start is
   * not an instruction yet: it is armed on the scheduler and hits the bus when its interval opens.
   * A re-transmitted mRID (status change, cancellation) updates the existing row rather than
   * duplicating it — 2030.5 servers re-send the same event on every state change, keyed by mRID.
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
    Instant start = saved.getIntervalStart();
    if (start.isAfter(clock.instant())) {
      scheduler.schedule(() -> publisher.publish(saved), start);
    } else {
      publisher.publish(saved);
    }
    return DerEventResponse.from(saved);
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
        mapper.writeValueAsString(request),
        lfdi);
  }

  private DerEvent apply(DerEvent existing, DerControlRequest request, String lfdi) {
    existing.setStatus(request.eventStatus());
    existing.setIntervalStart(request.interval().start());
    existing.setDurationSeconds(request.interval().durationSeconds());
    existing.setTargetActivePowerW(request.derControlBase().opModTargetW());
    existing.setEnergize(request.derControlBase().opModEnergize());
    existing.setSubmittedByLfdi(lfdi);
    return existing;
  }
}
