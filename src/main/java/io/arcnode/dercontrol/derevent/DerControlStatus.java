package io.arcnode.dercontrol.derevent;

/**
 * Lifecycle state of a DERControl event, from the IEEE 2030.5 {@code EventStatus.currentStatus}
 * field. 2030.5 code 3 ("Cancelled with Randomization") folds into {@link #CANCELLED} for MVP.
 *
 * <p>{@link #COMPLETED} (code 5) is IEEE 2030.5-2023 only — verified directly against the real
 * primary-source schema (IEEE's own free "Additional Resources" download, not a third-party
 * mirror): "the event has completed... after the event's maximum Effective Scheduled Period if the
 * event has not been cancelled." {@link #SUPERSEDED} (code 4) is deprecated in 2023 ("SHALL NOT be
 * used by servers") but kept here since 2030.5-2018 servers may still send it.
 */
public enum DerControlStatus {
  SCHEDULED,
  ACTIVE,
  CANCELLED,
  SUPERSEDED,
  COMPLETED
}
