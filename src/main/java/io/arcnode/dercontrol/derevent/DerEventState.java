package io.arcnode.dercontrol.derevent;

import java.time.Instant;

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
  REJECTED;

  /**
   * Resolves an event's state (ADR-002 §16): a terminal utility status (cancelled/superseded/
   * completed — withdrawn or naturally concluded) always wins; explicit rejection is terminal;
   * manual mode with no decision yet is pending; otherwise ACTIVE requires both the utility's own
   * status saying ACTIVE and the interval being open — 2030.5 servers retransmit status=Active when
   * an interval opens, so wall-clock time alone can't be trusted to self-declare activeness.
   *
   * @param event the event, with the utility's status and any operator decision
   * @param mode site dispatch policy (ADR-002 §16)
   * @param now wall-clock instant to compare against {@code interval.start}
   * @return the state to publish
   */
  public static DerEventState of(DerEvent event, DispatchMode mode, Instant now) {
    DerControlStatus status = event.getStatus();
    if (status == DerControlStatus.CANCELLED
        || status == DerControlStatus.SUPERSEDED
        || status == DerControlStatus.COMPLETED) {
      return IDLE;
    }
    Boolean approved = event.getApproved();
    if (Boolean.FALSE.equals(approved)) {
      return REJECTED;
    }
    if (mode == DispatchMode.MANUAL && approved == null) {
      return PENDING;
    }
    boolean intervalOpen = !now.isBefore(event.getIntervalStart());
    return status == DerControlStatus.ACTIVE && intervalOpen ? ACTIVE : ARMED;
  }
}
