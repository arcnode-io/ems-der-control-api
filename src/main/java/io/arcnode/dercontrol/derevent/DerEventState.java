package io.arcnode.dercontrol.derevent;

/**
 * Where one DER event sits in der-control-api's own dispatch pipeline — distinct from {@link
 * DerControlStatus}, which is the utility's lifecycle field. Published on the {@code
 * der_event_state} channel (named to avoid colliding with ems-industrial-gateway's own {@code
 * events/dispatch_state}, a generic per-command execution ack — a different concept entirely, not
 * this DER-specific lifecycle state); names match the template's {@code values:} labels exactly
 * (ground truth: edp-api's der_event_state measurement).
 */
public enum DerEventState {
  /** The utility withdrew the event (cancelled/superseded) — nothing in force. */
  IDLE,
  /** Manual mode, ingested, awaiting an operator's approve/reject command. */
  PENDING,
  /** Approved (or auto mode) but {@code interval.start} hasn't opened yet. */
  ARMED,
  /** Interval is open and the utility's own status confirms it — the setpoint is applied. */
  ACTIVE,
  /** An operator declined the event; it will not dispatch. */
  REJECTED
}
