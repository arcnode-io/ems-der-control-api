package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.RECEIVED_DOCUMENT;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.START;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.request;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.TestCerts;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — mocked repository + posture service. Storing and looking up events; what the bus is told
 * is DerEventPostureServiceTest's. AAA.
 */
@ExtendWith(MockitoExtension.class)
class DerEventServiceTest {

  @Mock private DerEventRepository repository;
  @Mock private DerEventPostureService posture;
  @Mock private EnvelopeFeedMonitor envelopeFeedMonitor;
  @Mock private EventLogService eventLog;

  private DerEventService service() {
    return new DerEventService(repository, posture, envelopeFeedMonitor, eventLog);
  }

  @Test
  void ingestSavesNewEventAndPublishesTheGoverningPosture() {
    // Arrange
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));

    // Act
    DerEventResponse result =
        service()
            .ingest(
                request("mrid-1", DerControlStatus.ACTIVE),
                RECEIVED_DOCUMENT,
                TestCerts.HEADER_VALUE);

    // Assert
    assertThat(result.mrid()).isEqualTo("mrid-1");
    assertThat(result.status()).isEqualTo(DerControlStatus.ACTIVE);
    assertThat(result.targetActivePowerW()).isEqualTo(-1_000_000.0);
    assertThat(result.submittedByLfdi()).isEqualTo(TestCerts.LFDI);
    ArgumentCaptor<DerEvent> published = ArgumentCaptor.forClass(DerEvent.class);
    verify(posture).publishGoverning(published.capture());
    assertThat(published.getValue().getMrid()).isEqualTo("mrid-1");
  }

  @Test
  void ingestUpdatesExistingEventOnRetransmit() {
    // Arrange: same mRID re-sent as Cancelled — updates the existing row, doesn't duplicate it
    DerEvent existing =
        withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1_000_000.0));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(existing));
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> inv.getArgument(0));

    // Act
    DerEventResponse result =
        service()
            .ingest(
                request("mrid-1", DerControlStatus.CANCELLED),
                RECEIVED_DOCUMENT,
                TestCerts.HEADER_VALUE);

    // Assert
    assertThat(result.status()).isEqualTo(DerControlStatus.CANCELLED);
    assertThat(result.submittedByLfdi()).isEqualTo(TestCerts.LFDI);
    verify(repository).save(existing);
  }

  @Test
  void ingestArmsTheRepublishAtStartAndTheReleaseAtEnd() {
    // Arrange
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    DerEvent saved = withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1.0));
    given(repository.save(any(DerEvent.class))).willReturn(saved);

    // Act
    service()
        .ingest(
            request("mrid-1", DerControlStatus.ACTIVE), RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert: both timers are the posture service's to arm, with the persisted event
    verify(posture).armFutureRepublish(saved);
    verify(posture).armRelease(saved);
  }

  @Test
  void findByMridReturnsResponseWhenPresent() {
    // Arrange
    DerEvent event = withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, 500.0));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(event));

    // Act
    Optional<DerEventResponse> result = service().findByMrid("mrid-1");

    // Assert
    assertThat(result).isPresent();
    assertThat(result.orElseThrow().mrid()).isEqualTo("mrid-1");
  }

  @Test
  void findByMridIsEmptyWhenMissing() {
    // Arrange
    given(repository.findByMrid("missing")).willReturn(Optional.empty());

    // Act
    Optional<DerEventResponse> result = service().findByMrid("missing");

    // Assert
    assertThat(result).isEmpty();
    verify(posture, never()).publishGoverning(any());
  }

  @Test
  void findByStatusReturnsMatchingEvents() {
    // Arrange
    DerEvent a = withId(1, curtailment("a", DerControlStatus.ACTIVE, START, null));
    DerEvent b = withId(2, curtailment("b", DerControlStatus.ACTIVE, START, null));
    given(repository.findByStatus(DerControlStatus.ACTIVE)).willReturn(List.of(a, b));

    // Act
    List<DerEventResponse> result = service().findByStatus(DerControlStatus.ACTIVE);

    // Assert
    assertThat(result).extracting(DerEventResponse::mrid).containsExactly("a", "b");
  }

  @Test
  void anEnvelopeArrivalTellsTheFeedMonitorHowLongItIsValidFor() {
    // Arrange: an envelope-only control — limits, no setpoint
    DerControlRequest envelope =
        new DerControlRequest(
            "mrid-envelope",
            DerControlStatus.ACTIVE,
            new DerControlRequest.Interval(START, 10L),
            new DerControlRequest.ControlBase(null, null, 500_000.0, 0.0),
            DerProgram.DLR_LINE_CONSTRAINT);
    given(repository.findByMrid("mrid-envelope")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(call -> call.getArgument(0));

    // Act
    service().ingest(envelope, RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert: the envelope declares its own validity, so staleness needs no tuned constant
    verify(envelopeFeedMonitor).recordEnvelope(START.plusSeconds(10L));
  }

  @Test
  void aCurtailmentDoesNotTouchTheEnvelopeFeedMonitor() {
    // Arrange: a target-mode control is not the envelope schedule, so it says nothing about whether
    // the envelope is still arriving
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(call -> call.getArgument(0));

    // Act
    service()
        .ingest(
            request("mrid-1", DerControlStatus.ACTIVE), RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert
    verify(envelopeFeedMonitor, never()).recordEnvelope(any());
  }
}
