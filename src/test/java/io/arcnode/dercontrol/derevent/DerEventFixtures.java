package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Builders shared by the DerEvent service tests. A plain class, referenced explicitly — nothing
 * here is auto-discovered.
 */
final class DerEventFixtures {

  static final Instant START = Instant.parse("2026-09-08T14:00:00Z");
  static final Instant NOW = Instant.parse("2026-09-08T13:00:00Z");

  // Reason: DerEvent.rawPayload keeps the document exactly as it arrived; the services never
  // re-read it, so a stand-in is enough — parsing is DerControlNotificationParserTest's.
  static final String RECEIVED_DOCUMENT = "<Notification/>";

  private static final String LFDI = "lfdi-1";
  private static final long AN_HOUR = 3600L;

  private DerEventFixtures() {}

  /**
   * A target-mode request (−1 MW, energize) under the line-constraint program, starting at START.
   */
  static DerControlRequest request(String mrid, DerControlStatus status) {
    return new DerControlRequest(
        mrid,
        status,
        new DerControlRequest.Interval(START, AN_HOUR),
        new DerControlRequest.ControlBase(-1_000_000.0, true, null, null),
        DerProgram.DLR_LINE_CONSTRAINT);
  }

  /** An hour-long target-mode event under the line-constraint program. */
  static DerEvent curtailment(
      String mrid, DerControlStatus status, Instant start, @Nullable Double targetW) {
    return curtailment(mrid, status, start, targetW, DerProgram.DLR_LINE_CONSTRAINT);
  }

  static DerEvent curtailment(
      String mrid,
      DerControlStatus status,
      Instant start,
      @Nullable Double targetW,
      DerProgram program) {
    return new DerEvent(
        mrid, status, start, AN_HOUR, targetW, true, null, null, RECEIVED_DOCUMENT, LFDI, program);
  }

  /** An envelope-only event: an import limit, no setpoint. */
  static DerEvent envelope(String mrid, Instant start, long durationSeconds, double importLimitW) {
    return new DerEvent(
        mrid,
        DerControlStatus.ACTIVE,
        start,
        durationSeconds,
        null,
        null,
        importLimitW,
        null,
        RECEIVED_DOCUMENT,
        LFDI,
        DerProgram.DLR_LINE_CONSTRAINT);
  }

  /** As the repository would hand it back: with a generated id. */
  static DerEvent withId(long id, DerEvent event) {
    ReflectionTestUtils.setField(event, "id", id);
    return event;
  }

  /** Pins receivedAt, which the constructor stamps with the wall clock. */
  static DerEvent receivedAt(DerEvent event, Instant receivedAt) {
    ReflectionTestUtils.setField(event, "receivedAt", receivedAt);
    return event;
  }
}
