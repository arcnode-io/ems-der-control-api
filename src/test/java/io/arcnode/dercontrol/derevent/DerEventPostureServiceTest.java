package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.NOW;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.envelope;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.receivedAt;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

/**
 * Unit — which event {@code der_dispatch} reflects: the governing one, resolved from the store. The
 * timers that re-publish at interval open and end are DerEventPostureServiceTimingTest's. AAA.
 */
@ExtendWith(MockitoExtension.class)
class DerEventPostureServiceTest {

  @Mock private DerEventRepository repository;
  @Mock private DispatchPublisher publisher;
  @Mock private TaskScheduler scheduler;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private DerEventPostureService posture() {
    return new DerEventPostureService(repository, publisher, clock, scheduler);
  }

  /** A curtailment that opened a minute ago and is still in force. */
  private static DerEvent inForce(long id, String mrid, double targetW, DerProgram program) {
    return withId(
        id, curtailment(mrid, DerControlStatus.ACTIVE, NOW.minusSeconds(60), targetW, program));
  }

  @Test
  void statesTheIdlePostureWhenNothingIsInForce() {
    // Arrange: a site that has never been curtailed. Nothing is persisted, and the broker's
    // retained state went with its container, so no consumer can know the site is uncommanded
    // unless this says so.
    given(repository.findByStatusIn(any())).willReturn(List.of());

    // Act
    posture().statePosture();

    // Assert
    verify(publisher).publishIdlePosture();
  }

  @Test
  void republishesAnInForceEventRatherThanClaimingIdle() {
    // Arrange: the process restarted mid-curtailment. Publishing the idle posture here would tell
    // every consumer the plant is free, which on the charge path means importing during an event.
    DerEvent inForce = inForce(1, "mrid-1", -1_000_000.0, DerProgram.DLR_LINE_CONSTRAINT);
    given(repository.findByStatusIn(any())).willReturn(List.of(inForce));

    // Act
    posture().statePosture();

    // Assert
    verify(publisher).publish(inForce);
    verify(publisher, never()).publishIdlePosture();
  }

  @Test
  void theHigherPrimacyProgramGovernsRegardlessOfArrivalOrder() {
    // Arrange: a flex call in force, then a line constraint arrives later. The constraint's
    // program outranks the flex program (lower primacy), so it governs der_dispatch — not the
    // event that happened to arrive last. Same rule 2030.5 gives for overlapping programs.
    // Reason: receivedAt is stamped in the constructor, so pin it — the constraint must have
    // arrived FIRST, or arrival order alone would pick it and prove nothing.
    DerEvent flex =
        receivedAt(inForce(1, "flex-1", 1_120_000.0, DerProgram.ERCOT_FLEX), NOW.minusSeconds(60));
    DerEvent constraint =
        receivedAt(
            inForce(2, "line-1", 0.0, DerProgram.DLR_LINE_CONSTRAINT), NOW.minusSeconds(120));
    given(repository.findByStatusIn(any())).willReturn(List.of(flex, constraint));

    // Act
    posture().statePosture();

    // Assert: primacy 0 outranks primacy 1 even though the flex call is the newer event
    verify(publisher).publish(constraint);
    verify(publisher, never()).publish(flex);
  }

  @Test
  void closingOneEventLeavesTheSiteDispatchedWhileAnotherIsStillInForce() {
    // Arrange: two overlapping curtailments; the utility closes A while B is still open
    DerEvent closing =
        withId(
            1,
            curtailment("mrid-a", DerControlStatus.CANCELLED, NOW.minusSeconds(60), -1_000_000.0));
    DerEvent stillInForce = inForce(2, "mrid-b", -900_000.0, DerProgram.DLR_LINE_CONSTRAINT);
    given(repository.findByStatusIn(any())).willReturn(List.of(stillInForce));

    // Act
    posture().publishGoverning(closing);

    // Assert: der_dispatch is one set of site-level channels, not per-mRID, so closing A has to
    // publish B — the event still in force. Publishing A's terminal state would write "released"
    // and a zero setpoint straight through to plant while B is still commanding one.
    verify(publisher).publish(stillInForce);
    verify(publisher, never()).publish(closing);
  }

  @Test
  void anEnvelopePublishesItselfRatherThanTheGoverningCurtailment() {
    // Arrange: an envelope refresh arrives while a curtailment is in force
    DerEvent refreshed = withId(1, envelope("env-1", NOW, 10L, 5_000_000.0));

    // Act
    posture().publishGoverning(refreshed);

    // Assert: an envelope carries only limits and never touches der_dispatch, so there is nothing
    // to resolve — the store is not even consulted
    verify(publisher).publish(refreshed);
    verify(repository, never()).findByStatusIn(any());
  }
}
