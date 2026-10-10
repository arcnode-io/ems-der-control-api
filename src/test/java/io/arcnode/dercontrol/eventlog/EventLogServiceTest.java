package io.arcnode.dercontrol.eventlog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DerProgram;
import io.arcnode.dercontrol.derevent.DispatchMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit — mocked repository. What gets written, and when nothing does. AAA. */
@ExtendWith(MockitoExtension.class)
class EventLogServiceTest {

  private static final Instant NOW = Instant.parse("2026-10-10T09:23:17Z");
  private static final Instant START = Instant.parse("2026-10-10T09:23:00Z");

  @Mock private EventLogRepository repository;

  private EventLogService service() {
    return new EventLogService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static DerEvent lineConstraint() {
    return new DerEvent(
        "mrid-1",
        DerControlStatus.ACTIVE,
        START,
        3600L,
        null,
        null,
        0.0,
        null,
        "<Notification/>",
        "lfdi-1",
        DerProgram.DLR_LINE_CONSTRAINT);
  }

  @Test
  void derEventStateWritesARowWhenThePostureChanged() {
    // Arrange: the last thing recorded for this mRID was ARMED
    given(
            repository.findFirstBySubjectAndTypeOrderByOccurredAtDesc(
                "mrid-1", EventType.DER_EVENT_STATE))
        .willReturn(
            Optional.of(
                new EventLog(
                    START,
                    EventType.DER_EVENT_STATE,
                    "mrid-1",
                    null,
                    DerProgram.DLR_LINE_CONSTRAINT,
                    DerControlStatus.ACTIVE,
                    DerEventState.ARMED,
                    null,
                    null)));

    // Act
    service().derEventState(lineConstraint(), DerEventState.ACTIVE);

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.DER_EVENT_STATE);
    assertThat(written.getValue().getSubject()).isEqualTo("mrid-1");
    assertThat(written.getValue().getState()).isEqualTo(DerEventState.ACTIVE);
    assertThat(written.getValue().getProgram()).isEqualTo(DerProgram.DLR_LINE_CONSTRAINT);
    assertThat(written.getValue().getStatus()).isEqualTo(DerControlStatus.ACTIVE);
    assertThat(written.getValue().getOccurredAt()).isEqualTo(NOW);
    assertThat(written.getValue().getActor()).isNull();
  }

  @Test
  void derEventStateWritesNothingWhenThePostureIsUnchanged() {
    // Arrange: a boot or reconnect republish restates ACTIVE, which is already the last row
    given(
            repository.findFirstBySubjectAndTypeOrderByOccurredAtDesc(
                "mrid-1", EventType.DER_EVENT_STATE))
        .willReturn(
            Optional.of(
                new EventLog(
                    START,
                    EventType.DER_EVENT_STATE,
                    "mrid-1",
                    null,
                    DerProgram.DLR_LINE_CONSTRAINT,
                    DerControlStatus.ACTIVE,
                    DerEventState.ACTIVE,
                    null,
                    null)));

    // Act
    service().derEventState(lineConstraint(), DerEventState.ACTIVE);

    // Assert
    verify(repository, never()).save(any());
  }

  @Test
  void derEventStateWritesTheFirstRowForAnUnseenEvent() {
    // Arrange: nothing recorded yet for this mRID
    given(
            repository.findFirstBySubjectAndTypeOrderByOccurredAtDesc(
                "mrid-1", EventType.DER_EVENT_STATE))
        .willReturn(Optional.empty());

    // Act
    service().derEventState(lineConstraint(), DerEventState.ARMED);

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getState()).isEqualTo(DerEventState.ARMED);
  }

  @Test
  void aReceivedEventIsLoggedWithTheSubmitterAsActor() {
    // Arrange
    DerEvent event = lineConstraint();

    // Act
    service().derEventReceived(event, "lfdi-1");

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.DER_EVENT_RECEIVED);
    assertThat(written.getValue().getSubject()).isEqualTo("mrid-1");
    assertThat(written.getValue().getActor()).isEqualTo("lfdi-1");
    assertThat(written.getValue().getProgram()).isEqualTo(DerProgram.DLR_LINE_CONSTRAINT);
    assertThat(written.getValue().getStatus()).isEqualTo(DerControlStatus.ACTIVE);
    assertThat(written.getValue().getState()).isNull();
    assertThat(written.getValue().getOccurredAt()).isEqualTo(NOW);
  }

  @Test
  void aRetransmitIsLoggedAsAnUpdateCarryingTheNewStatus() {
    // Arrange
    DerEvent event = lineConstraint();
    event.setStatus(DerControlStatus.CANCELLED);

    // Act
    service().derEventUpdated(event, "lfdi-1");

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.DER_EVENT_UPDATED);
    assertThat(written.getValue().getStatus()).isEqualTo(DerControlStatus.CANCELLED);
    assertThat(written.getValue().getActor()).isEqualTo("lfdi-1");
  }

  @Test
  void aRejectionIsLoggedAgainstTheOperator() {
    // Arrange
    DerEvent event = lineConstraint();

    // Act
    service().derEventDecided(event, false);

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.DER_EVENT_REJECTED);
    assertThat(written.getValue().getActor()).isEqualTo("operator");
    assertThat(written.getValue().getSubject()).isEqualTo("mrid-1");
  }

  @Test
  void aReserveChangeIsLoggedWithItsValue() {
    // Arrange

    // Act
    service().operatorReserveSet(7_714_286.0);

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.OPERATOR_RESERVE_SET);
    assertThat(written.getValue().getSubject()).isEqualTo("operator_reserve");
    assertThat(written.getValue().getActor()).isEqualTo("operator");
    assertThat(written.getValue().getValue()).isEqualTo(7_714_286.0);
    assertThat(written.getValue().getOccurredAt()).isEqualTo(NOW);
  }

  @Test
  void aModeChangeIsLoggedWithTheModeAsDetail() {
    // Arrange

    // Act
    service().dispatchModeSet(DispatchMode.MANUAL);

    // Assert
    ArgumentCaptor<EventLog> written = ArgumentCaptor.forClass(EventLog.class);
    verify(repository).save(written.capture());
    assertThat(written.getValue().getType()).isEqualTo(EventType.DISPATCH_MODE_SET);
    assertThat(written.getValue().getSubject()).isEqualTo("dispatch_mode");
    assertThat(written.getValue().getDetail()).isEqualTo("MANUAL");
    assertThat(written.getValue().getActor()).isEqualTo("operator");
  }
}
