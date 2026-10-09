package io.arcnode.dercontrol.derevent;

import static io.arcnode.dercontrol.derevent.DerEventFixtures.NOW;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.START;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.curtailment;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.envelope;
import static io.arcnode.dercontrol.derevent.DerEventFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.dispatch.DispatchPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;

/**
 * Unit — when {@code der_dispatch} re-publishes by itself: at interval open, at interval end, and
 * again after a restart that lost the in-memory scheduler. AAA.
 */
@ExtendWith(MockitoExtension.class)
class DerEventPostureServiceTimingTest {

  private static final long AN_HOUR = 3600L;

  @Mock private DerEventRepository repository;
  @Mock private DispatchPublisher publisher;
  @Mock private TaskScheduler scheduler;
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private DerEventPostureService posture() {
    return new DerEventPostureService(repository, publisher, clock, scheduler);
  }

  /** A curtailment that opened a minute before NOW. */
  private static DerEvent underway(String mrid) {
    return withId(1, curtailment(mrid, DerControlStatus.ACTIVE, NOW.minusSeconds(60), -1.0e6));
  }

  @Test
  void armsARepublishAtIntervalStartWhenItHasNotOpenedYet() {
    // Arrange: START is after NOW, so ARMED has to flip to ACTIVE by itself when it opens
    DerEvent armed = withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1.0e6));

    // Act
    posture().armFutureRepublish(armed);

    // Assert
    verify(scheduler).schedule(any(Runnable.class), eq(START));
  }

  @Test
  void armsNoRepublishAtAStartThatHasAlreadyPassed() {
    // Arrange
    DerEvent open = underway("mrid-1");

    // Act
    posture().armFutureRepublish(open);

    // Assert
    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void armsAReleaseAtTheEndOfTheInterval() {
    // Arrange: an event in force now, ending an hour out
    DerEvent open = underway("mrid-1");

    // Act
    posture().armRelease(open);

    // Assert: scheduled for interval end. Without this the plant keeps executing an expired
    // setpoint until the utility happens to send a terminal status — a DERMS that crashes or
    // partitions leaves storage discharging to its floor.
    verify(scheduler).schedule(any(Runnable.class), eq(NOW.minusSeconds(60).plusSeconds(AN_HOUR)));
  }

  @Test
  void armsNoReleaseForAnEnvelopeOnlyEvent() {
    // Arrange: the operating envelope re-POSTs continuously under one mRID with a fresh short
    // interval each time
    DerEvent refreshed = withId(1, envelope("env-1", NOW, 10L, 5_000_000.0));

    // Act
    posture().armRelease(refreshed);

    // Assert: an envelope never publishes der_dispatch, so it has nothing to release — and arming
    // one per re-POST would queue a task every few seconds for the life of the process.
    verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
  }

  @Test
  void rearmsPersistedFutureEventsOnStartup() {
    // Arrange: a restart-recovery scenario — an event was persisted with a future interval.start
    // before the process died, and the in-memory TaskScheduler lost its scheduled task with it.
    DerEvent armed = withId(1, curtailment("mrid-1", DerControlStatus.ACTIVE, START, -1.0e6));
    given(repository.findByIntervalStartAfter(NOW)).willReturn(List.of(armed));

    // Act
    posture().rearmFutureEvents();

    // Assert
    verify(scheduler).schedule(any(Runnable.class), eq(START));
  }

  @Test
  void rearmsTheReleaseOfAnEventAlreadyUnderwayOnBoot() {
    // Arrange: a restart mid-event. The release armed at ingest died with the process — and the
    // event's own interval is the only thing that still knows when it ends.
    given(repository.findByStatusIn(any())).willReturn(List.of(underway("mrid-1")));

    // Act
    posture().rearmFutureEvents();

    // Assert
    verify(scheduler).schedule(any(Runnable.class), eq(NOW.plusSeconds(AN_HOUR - 60)));
  }

  @Test
  void releasesFromTheStoreRatherThanTheEventItWasArmedWith() {
    // Arrange: an event in force, armed for release at its interval end
    DerEvent inForce = underway("mrid-1");
    given(repository.findByStatusIn(any())).willReturn(List.of(inForce));
    posture().rearmFutureEvents();
    ArgumentCaptor<Runnable> armed = ArgumentCaptor.forClass(Runnable.class);
    verify(scheduler).schedule(armed.capture(), any(Instant.class));
    // the utility closed it early, so by the time the release fires nothing is open
    given(repository.findByStatusIn(any())).willReturn(List.of());

    // Act
    armed.getValue().run();

    // Assert: the posture comes from the store at fire time. Holding the entity would republish
    // the status it had when armed — re-asserting a curtailment the utility already ended, which
    // is worse than never releasing at all.
    verify(publisher).publishIdlePosture();
    verify(publisher, never()).publish(inForce);
  }
}
