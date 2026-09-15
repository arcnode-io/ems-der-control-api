package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.dercontrol.Config;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit — pure derivation of {@link DispatchState} from event + mode + wall clock. AAA. */
class DispatchStateTest {

  private static final Instant START = Instant.parse("2026-09-08T14:00:00Z");
  private static final Instant BEFORE_START = START.minusSeconds(60);
  private static final Instant AFTER_START = START.plusSeconds(60);

  private static DerEvent event(DerControlStatus status) {
    return new DerEvent("mrid-1", status, START, 3600L, -1_000_000.0, true, "{}", "lfdi-1");
  }

  @Test
  void cancelledIsIdleRegardlessOfApprovalOrTime() {
    // Arrange
    DerEvent event = event(DerControlStatus.CANCELLED);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.MANUAL, AFTER_START))
        .isEqualTo(DispatchState.IDLE);
  }

  @Test
  void supersededIsIdle() {
    // Arrange
    DerEvent event = event(DerControlStatus.SUPERSEDED);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.AUTO, AFTER_START))
        .isEqualTo(DispatchState.IDLE);
  }

  @Test
  void explicitlyRejectedIsRejectedEvenAfterStart() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);
    event.setApproved(false);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.MANUAL, AFTER_START))
        .isEqualTo(DispatchState.REJECTED);
  }

  @Test
  void manualModeAwaitingDecisionIsPending() {
    // Arrange: manual mode, no approve/reject received yet
    DerEvent event = event(DerControlStatus.SCHEDULED);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.MANUAL, BEFORE_START))
        .isEqualTo(DispatchState.PENDING);
  }

  @Test
  void autoModeNeverPendsEvenWithNoApprovalRecorded() {
    // Arrange: auto mode never asks, approved stays null forever
    DerEvent event = event(DerControlStatus.SCHEDULED);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.AUTO, BEFORE_START))
        .isEqualTo(DispatchState.ARMED);
  }

  @Test
  void approvedBeforeIntervalStartIsArmed() {
    // Arrange
    DerEvent event = event(DerControlStatus.SCHEDULED);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.MANUAL, BEFORE_START))
        .isEqualTo(DispatchState.ARMED);
  }

  @Test
  void approvedAfterIntervalStartIsActive() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.MANUAL, AFTER_START))
        .isEqualTo(DispatchState.ACTIVE);
  }

  @Test
  void autoModeAfterIntervalStartIsActive() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(event.dispatchState(Config.DispatchMode.AUTO, AFTER_START))
        .isEqualTo(DispatchState.ACTIVE);
  }
}
