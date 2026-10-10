package io.arcnode.dercontrol.eventlog.dto;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DerProgram;
import io.arcnode.dercontrol.eventlog.EventLog;
import io.arcnode.dercontrol.eventlog.EventType;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One row of the site's event history, as the HMI reads it. Fields absent for a type are null. */
public record EventLogResponse(
    long id,
    Instant occurredAt,
    EventType type,
    String subject,
    @Nullable String actor,
    @Nullable DerProgram program,
    @Nullable DerControlStatus status,
    @Nullable DerEventState state,
    @Nullable Double value,
    @Nullable String detail) {

  public static EventLogResponse from(EventLog row) {
    return new EventLogResponse(
        row.getId(),
        row.getOccurredAt(),
        row.getType(),
        row.getSubject(),
        row.getActor(),
        row.getProgram(),
        row.getStatus(),
        row.getState(),
        row.getValue(),
        row.getDetail());
  }
}
