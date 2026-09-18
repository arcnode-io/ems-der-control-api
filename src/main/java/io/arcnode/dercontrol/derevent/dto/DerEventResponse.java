package io.arcnode.dercontrol.derevent.dto;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Response shape for the der-events endpoints — the JPA entity never leaves the service layer. */
public record DerEventResponse(
    String mrid,
    DerControlStatus status,
    Instant intervalStart,
    long durationSeconds,
    @Nullable Double targetActivePowerW,
    @Nullable Boolean energize,
    Instant receivedAt,
    String submittedByLfdi) {

  public static DerEventResponse from(DerEvent event) {
    return new DerEventResponse(
        event.getMrid(),
        event.getStatus(),
        event.getIntervalStart(),
        event.getDurationSeconds(),
        event.getTargetActivePowerW(),
        event.getEnergize(),
        event.getReceivedAt(),
        event.getSubmittedByLfdi());
  }
}
