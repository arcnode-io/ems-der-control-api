package io.arcnode.dercontrol.derevent;

/**
 * Where one DER event sits in der-control-api's own dispatch pipeline — distinct from {@link
 * DerControlStatus}, which is the utility's lifecycle field. Published on the {@code
 * dispatch_state} channel; names match the template's {@code values:} labels exactly.
 */
public enum DispatchState {
  /** The utility withdrew the event (cancelled/superseded) — nothing in force. */
  IDLE,
  /** Manual mode, ingested, awaiting an operator's approve/reject command. */
  PENDING,
  /** Approved (or auto mode) but {@code interval.start} hasn't opened yet. */
  ARMED,
  /** Interval is open — the setpoint is (or should be) applied. */
  ACTIVE,
  /** An operator declined the event; it will not dispatch. */
  REJECTED
}
