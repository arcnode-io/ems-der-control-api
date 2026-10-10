package io.arcnode.dercontrol.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DerProgram;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — the posture a publish resolves is handed to the event log. AAA. */
@ExtendWith(MockitoExtension.class)
class DispatchPublisherEventLogTest {

  private static final Instant NOW = Instant.parse("2026-10-10T09:23:17Z");

  @Mock private MeasurementPublisher bus;
  @Mock private DispatchSettingsService dispatchSettings;
  @Mock private EventLogService eventLog;

  private DispatchPublisher publisher() {
    return new DispatchPublisher(bus, Clock.fixed(NOW, ZoneOffset.UTC), dispatchSettings, eventLog);
  }

  @Test
  void publishingAnOpenEventRecordsItsResolvedPosture() {
    // Arrange: an active line-constraint call whose interval opened a minute ago
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.AUTO);
    DerEvent event =
        new DerEvent(
            "mrid-1",
            DerControlStatus.ACTIVE,
            NOW.minusSeconds(60),
            3600L,
            -1_000_000.0,
            true,
            null,
            null,
            "<Notification/>",
            "lfdi-1",
            DerProgram.DLR_LINE_CONSTRAINT);

    // Act
    publisher().publish(event);

    // Assert
    verify(eventLog).derEventState(event, DerEventState.ACTIVE);
  }

  @Test
  void anEnvelopeRefreshIsNotAnEvent() {
    // Arrange: limits only, re-sent every few seconds under one mRID
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.AUTO);
    DerEvent envelope =
        new DerEvent(
            "env-1",
            DerControlStatus.ACTIVE,
            NOW.minusSeconds(1),
            10L,
            null,
            null,
            6_864_000.0,
            null,
            "<Notification/>",
            "lfdi-1",
            DerProgram.DLR_LINE_CONSTRAINT);

    // Act
    publisher().publish(envelope);

    // Assert
    verify(eventLog, never()).derEventState(any(), any());
  }
}
