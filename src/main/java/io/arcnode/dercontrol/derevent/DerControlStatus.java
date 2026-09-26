package io.arcnode.dercontrol.derevent;

/**
 * Lifecycle state of a DERControl event, from the IEEE 2030.5 {@code EventStatus.currentStatus}
 * field. 2030.5 code 3 ("Cancelled with Randomization") folds into {@link #CANCELLED} for MVP.
 *
 * <p>{@link #COMPLETED} (code 5): "the event has completed... after the event's maximum Effective
 * Scheduled Period if the event has not been cancelled." {@link #SUPERSEDED} (code 4) is deprecated
 * ("SHALL NOT be used by servers") but kept here since older-edition servers may still send it.
 */
public enum DerControlStatus {
  SCHEDULED(0),
  ACTIVE(1),
  CANCELLED(2),
  SUPERSEDED(4),
  COMPLETED(5);

  // Reason: code 3 is "Cancelled with Randomization". Nothing here randomizes start or duration, so
  // an inbound 3 is treated as a plain cancellation rather than rejected.
  private static final short CANCELLED_WITH_RANDOMIZATION = 3;

  private final short currentStatus;

  DerControlStatus(int currentStatus) {
    this.currentStatus = (short) currentStatus;
  }

  /** The {@code EventStatus.currentStatus} code for this state. */
  public short currentStatus() {
    return currentStatus;
  }

  /**
   * @param code an inbound {@code EventStatus.currentStatus}
   * @throws IllegalArgumentException if no state carries that code
   */
  public static DerControlStatus fromCode(short code) {
    if (code == CANCELLED_WITH_RANDOMIZATION) {
      return CANCELLED;
    }
    for (DerControlStatus status : values()) {
      if (status.currentStatus == code) {
        return status;
      }
    }
    throw new IllegalArgumentException("unknown EventStatus.currentStatus code: " + code);
  }
}
