package io.arcnode.dercontrol.eventlog;

/** What kind of thing happened. Names are the {@code event_log.type} check constraint's values. */
public enum EventType {
  /** A DERControl arrived under an mRID this site had not seen. */
  DER_EVENT_RECEIVED,
  /** The utility re-sent a known mRID (a status change, a cancellation). */
  DER_EVENT_UPDATED,
  /** The site's resolved posture for an event changed (ARMED → ACTIVE, ACTIVE → IDLE, ...). */
  DER_EVENT_STATE,
  /** An operator approved a pending event in manual mode. */
  DER_EVENT_APPROVED,
  /** An operator rejected a pending event in manual mode. */
  DER_EVENT_REJECTED,
  /** An operator changed the energy held back from answering the envelope. */
  OPERATOR_RESERVE_SET,
  /** An operator switched the site between auto and manual dispatch. */
  DISPATCH_MODE_SET
}
