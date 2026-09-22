package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit — pure derivation of {@link DerEventState} from event + mode + wall clock. AAA. */
class DerEventStateTest {

  private static final Instant START = Instant.parse("2026-09-08T14:00:00Z");
  private static final Instant BEFORE_START = START.minusSeconds(60);
  private static final Instant AFTER_START = START.plusSeconds(60);

  private static DerEvent event(DerControlStatus status) {
    return new DerEvent(
        "mrid-1", status, START, 3600L, -1_000_000.0, true, null, null, "{}", "lfdi-1");
  }

  @Test
  void cancelledIsIdleRegardlessOfApprovalOrTime() {
    // Arrange
    DerEvent event = event(DerControlStatus.CANCELLED);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.MANUAL, AFTER_START)).isEqualTo(DerEventState.IDLE);
  }

  @Test
  void supersededIsIdle() {
    // Arrange
    DerEvent event = event(DerControlStatus.SUPERSEDED);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.AUTO, AFTER_START)).isEqualTo(DerEventState.IDLE);
  }

  @Test
  void completedIsIdle() {
    // Arrange
    DerEvent event = event(DerControlStatus.COMPLETED);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.AUTO, AFTER_START)).isEqualTo(DerEventState.IDLE);
  }

  @Test
  void explicitlyRejectedIsRejectedEvenAfterStart() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);
    event.setApproved(false);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.MANUAL, AFTER_START))
        .isEqualTo(DerEventState.REJECTED);
  }

  @Test
  void manualModeAwaitingDecisionIsPending() {
    // Arrange: manual mode, no approve/reject received yet
    DerEvent event = event(DerControlStatus.SCHEDULED);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.MANUAL, BEFORE_START))
        .isEqualTo(DerEventState.PENDING);
  }

  @Test
  void autoModeNeverPendsEvenWithNoApprovalRecorded() {
    // Arrange: auto mode never asks, approved stays null forever
    DerEvent event = event(DerControlStatus.SCHEDULED);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.AUTO, BEFORE_START)).isEqualTo(DerEventState.ARMED);
  }

  @Test
  void approvedBeforeIntervalStartIsArmed() {
    // Arrange
    DerEvent event = event(DerControlStatus.SCHEDULED);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.MANUAL, BEFORE_START))
        .isEqualTo(DerEventState.ARMED);
  }

  @Test
  void approvedAfterIntervalStartIsActive() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);
    event.setApproved(true);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.MANUAL, AFTER_START))
        .isEqualTo(DerEventState.ACTIVE);
  }

  @Test
  void autoModeAfterIntervalStartIsActive() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.AUTO, AFTER_START)).isEqualTo(DerEventState.ACTIVE);
  }

  @Test
  void staysArmedAfterIntervalStartIfUtilityNeverSentAnActiveRetransmission() {
    // Arrange: 2030.5 servers are expected to retransmit status=Active when the interval opens
    // (that's how the utility signals "this is live now"). If that retransmission never arrives,
    // der-control-api must not infer activeness from wall-clock time alone and self-declare it —
    // the utility's own status field stays authoritative for "is this actually in force."
    DerEvent event = event(DerControlStatus.SCHEDULED);

    // Act / Assert
    assertThat(event.derEventState(DispatchMode.AUTO, AFTER_START)).isEqualTo(DerEventState.ARMED);
  }

  @Test
  void isActiveDerivesFromDerEventState() {
    // Arrange
    DerEvent event = event(DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(event.isActive(DispatchMode.AUTO, AFTER_START)).isTrue();
    assertThat(event.isActive(DispatchMode.AUTO, BEFORE_START)).isFalse();
  }
}
