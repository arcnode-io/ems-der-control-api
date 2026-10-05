package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.TestCerts;
import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import io.arcnode.dercontrol.dispatch.EnvelopeFeedMonitor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit — mocked repository + publisher. The received document is stored verbatim, so these tests
 * pass a stand-in for it rather than a full Notification; parsing is covered by
 * DerControlNotificationParserTest. AAA.
 */
@ExtendWith(MockitoExtension.class)
class DerEventServiceTest {

  private static final Instant START = Instant.parse("2026-09-08T14:00:00Z");
  private static final Instant NOW = Instant.parse("2026-09-08T13:00:00Z");

  // Reason: DerEvent.rawPayload keeps the document exactly as it arrived; its content is
  // irrelevant to this service, which never re-reads it.
  private static final String RECEIVED_DOCUMENT = "<Notification/>";

  @Mock private DerEventRepository repository;
  @Mock private DispatchPublisher publisher;
  @Mock private EnvelopeFeedMonitor envelopeFeedMonitor;
  @Mock private TaskScheduler scheduler;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private DerEventService service() {
    return new DerEventService(repository, publisher, envelopeFeedMonitor, clock, scheduler);
  }

  private static DerControlRequest request(String mrid, DerControlStatus status) {
    return new DerControlRequest(
        mrid,
        status,
        new DerControlRequest.Interval(START, 3600L),
        new DerControlRequest.ControlBase(-1_000_000.0, true, null, null));
  }

  private static DerEvent withId(long id, DerEvent event) {
    ReflectionTestUtils.setField(event, "id", id);
    return event;
  }

  @Test
  void ingestSavesNewEventAndPublishes() {
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
    verify(publisher).publish(published.capture());
    assertThat(published.getValue().getMrid()).isEqualTo("mrid-1");
  }

  @Test
  void ingestUpdatesExistingEventOnRetransmit() {
    // Arrange: same mRID re-sent as Cancelled — updates the existing row, doesn't duplicate it
    DerEvent existing =
        withId(
            1,
            new DerEvent(
                "mrid-1",
                DerControlStatus.ACTIVE,
                START,
                3600L,
                -1_000_000.0,
                true,
                null,
                null,
                "{}",
                "old-lfdi"));
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
  void findByMridReturnsResponseWhenPresent() {
    // Arrange
    DerEvent event =
        withId(
            1,
            new DerEvent(
                "mrid-1",
                DerControlStatus.ACTIVE,
                START,
                3600L,
                500.0,
                null,
                null,
                null,
                "{}",
                "lfdi-1"));
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
    verify(publisher, never()).publish(any());
  }

  @Test
  void ingestPublishesImmediatelyAndArmsFutureRepublishWhenIntervalNotYetOpen() {
    // Arrange: START is after the fixed NOW — interval hasn't opened yet
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));

    // Act
    service()
        .ingest(
            request("mrid-1", DerControlStatus.ACTIVE), RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert: PENDING/ARMED visible right away, and re-armed for when the interval opens
    verify(publisher, times(1)).publish(any(DerEvent.class));
    verify(scheduler).schedule(any(Runnable.class), eq(START));
  }

  @Test
  void ingestDoesNotArmARepublishWhenIntervalAlreadyOpen() {
    // Arrange: an event whose interval.start is already in the past relative to NOW
    given(repository.findByMrid("mrid-1")).willReturn(Optional.empty());
    given(repository.save(any(DerEvent.class))).willAnswer(inv -> withId(1, inv.getArgument(0)));
    DerControlRequest openRequest =
        new DerControlRequest(
            "mrid-1",
            DerControlStatus.ACTIVE,
            new DerControlRequest.Interval(NOW.minusSeconds(60), 3600L),
            new DerControlRequest.ControlBase(-1_000_000.0, true, null, null));

    // Act
    service().ingest(openRequest, RECEIVED_DOCUMENT, TestCerts.HEADER_VALUE);

    // Assert: still publishes once, but nothing left to arm
    verify(publisher, times(1)).publish(any(DerEvent.class));
    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void rearmsPersistedFutureEventsOnStartup() {
    // Arrange: a restart-recovery scenario — an event was persisted with a future interval.start
    // before the process died, and the in-memory TaskScheduler lost its scheduled task with it.
    DerEvent armed =
        withId(
            1,
            new DerEvent(
                "mrid-1",
                DerControlStatus.ACTIVE,
                START,
                3600L,
                -1_000_000.0,
                true,
                null,
                null,
                "{}",
                "lfdi-1"));
    given(repository.findByIntervalStartAfter(NOW)).willReturn(List.of(armed));

    // Act
    service().rearmFutureEvents();

    // Assert
    verify(scheduler).schedule(any(Runnable.class), eq(START));
  }

  @Test
  void statesTheIdlePostureOnBootWhenNothingIsInForce() {
    // Arrange: a site that has never been curtailed. Nothing is persisted, and the broker's
    // retained state went with its container, so no consumer can know the site is uncommanded
    // unless this says so.
    given(repository.findByStatusIn(any())).willReturn(List.of());

    // Act
    service().statePosture();

    // Assert
    verify(publisher).publishIdlePosture();
  }

  @Test
  void republishesAnInForceEventOnBootRatherThanClaimingIdle() {
    // Arrange: the process restarted mid-curtailment. Publishing the idle posture here would tell
    // every consumer the plant is free, which on the charge path means importing during an event.
    DerEvent inForce =
        withId(
            1,
            new DerEvent(
                "mrid-1",
                DerControlStatus.ACTIVE,
                NOW.minusSeconds(60),
                3600L,
                -1_000_000.0,
                true,
                null,
                null,
                RECEIVED_DOCUMENT,
                "lfdi-1"));
    given(repository.findByStatusIn(any())).willReturn(List.of(inForce));

    // Act
    service().statePosture();

    // Assert
    verify(publisher).publish(inForce);
    verify(publisher, never()).publishIdlePosture();
  }

  @Test
  void approveCurrentPendingByMridTargetsThatExactEvent() {
    // Arrange: two events pending at once — mrid disambiguates which one
    DerEvent target = withId(1, event("mrid-1", null));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(target));
    given(repository.save(target)).willReturn(target);

    // Act
    service().approveCurrentPending("mrid-1");

    // Assert
    assertThat(target.getApproved()).isTrue();
    verify(publisher).publish(target);
    verify(repository, never()).findFirstByApprovedIsNullOrderByIntervalStartAsc();
  }

  @Test
  void rejectCurrentPendingByMridTargetsThatExactEvent() {
    // Arrange
    DerEvent target = withId(1, event("mrid-1", null));
    given(repository.findByMrid("mrid-1")).willReturn(Optional.of(target));
    given(repository.save(target)).willReturn(target);

    // Act
    service().rejectCurrentPending("mrid-1");

    // Assert
    assertThat(target.getApproved()).isFalse();
    verify(publisher).publish(target);
  }

  @Test
  void approveCurrentPendingWithNoMridFallsBackToNearestIntervalStart() {
    // Arrange: no mrid on the command (fixed commands/{verb}/event_active/none topic shape has no
    // slot for one) — resolve whichever still-undecided event is nearest its interval.start
    DerEvent nearest = withId(1, event("mrid-1", null));
    given(repository.findFirstByApprovedIsNullOrderByIntervalStartAsc())
        .willReturn(Optional.of(nearest));
    given(repository.save(nearest)).willReturn(nearest);

    // Act
    service().approveCurrentPending(null);

    // Assert
    assertThat(nearest.getApproved()).isTrue();
    verify(publisher).publish(nearest);
  }

  @Test
  void decideCurrentPendingIsANoOpWhenNothingResolves() {
    // Arrange: no mrid given, nothing currently undecided
    given(repository.findFirstByApprovedIsNullOrderByIntervalStartAsc())
        .willReturn(Optional.empty());

    // Act
    service().approveCurrentPending(null);

    // Assert
    verify(publisher, never()).publish(any());
  }

  private static DerEvent event(String mrid, Boolean approved) {
    DerEvent event =
        new DerEvent(
            mrid, DerControlStatus.SCHEDULED, START, 3600L, null, null, null, null, "{}", "lfdi-1");
    event.setApproved(approved);
    return event;
  }

  @Test
  void findByStatusReturnsMatchingEvents() {
    // Arrange
    DerEvent a =
        withId(
            1,
            new DerEvent(
                "a", DerControlStatus.ACTIVE, START, 60L, null, null, null, null, "{}", "lfdi-a"));
    DerEvent b =
        withId(
            2,
            new DerEvent(
                "b", DerControlStatus.ACTIVE, START, 60L, null, null, null, null, "{}", "lfdi-b"));
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
            new DerControlRequest.ControlBase(null, null, 500_000.0, 0.0));
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

  @Test
  void closingOneEventLeavesTheSiteDispatchedWhileAnotherIsStillInForce() {
    // Arrange: two overlapping curtailment events, both with an interval already open
    DerEvent closing = withId(1, inForce("mrid-a", -1_000_000.0));
    DerEvent stillInForce = withId(2, inForce("mrid-b", -900_000.0));
    given(repository.findByMrid("mrid-a")).willReturn(Optional.of(closing));
    given(repository.save(any(DerEvent.class))).willAnswer(call -> call.getArgument(0));
    given(repository.findByStatusIn(any())).willReturn(List.of(stillInForce));

    // Act: the utility closes A
    service()
        .ingest(
            new DerControlRequest(
                "mrid-a",
                DerControlStatus.CANCELLED,
                new DerControlRequest.Interval(NOW.minusSeconds(60), 3600L),
                new DerControlRequest.ControlBase(-1_000_000.0, true, null, null)),
            RECEIVED_DOCUMENT,
            TestCerts.HEADER_VALUE);

    // Assert: der_dispatch is one set of site-level channels, not per-mRID, so closing A has to
    // publish B — the event still in force. Publishing A's terminal state would write "released"
    // and a zero setpoint straight through to plant while B is still commanding one.
    ArgumentCaptor<DerEvent> published = ArgumentCaptor.forClass(DerEvent.class);
    verify(publisher).publish(published.capture());
    assertThat(published.getValue().getMrid()).isEqualTo("mrid-b");
  }

  private static DerEvent inForce(String mrid, double targetW) {
    return new DerEvent(
        mrid,
        DerControlStatus.ACTIVE,
        NOW.minusSeconds(60),
        3600L,
        targetW,
        true,
        null,
        null,
        RECEIVED_DOCUMENT,
        TestCerts.LFDI);
  }
}
