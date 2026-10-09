package io.arcnode.dercontrol.derevent;

import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

/**
 * The IEEE 2030.5 {@code DERControlBase} modes a control carries, as persisted on {@link DerEvent}.
 * Modes are orthogonal and each is optional: a status-only retransmission (e.g. Cancelled) carries
 * none, an operating envelope carries limits and no target, a dispatch carries a target.
 *
 * @param targetActivePowerW opModTargetW — real power target, watts, signed
 * @param energize opModEnergize — commanded energize/connect state
 * @param importLimitW opModImpLimW — import limit (envelope mode), watts
 * @param exportLimitW opModExpLimW — export limit (envelope mode), watts
 */
@Embeddable
public record DerControlBase(
    @Nullable Double targetActivePowerW,
    @Nullable Boolean energize,
    @Nullable Double importLimitW,
    @Nullable Double exportLimitW) {

  /** No modes at all — what a terminal retransmission carries. */
  public static final DerControlBase NONE = new DerControlBase(null, null, null, null);

  /**
   * True when only envelope modes are present — an import/export limit and no real-power setpoint.
   * Which modes are present is what distinguishes a standing operating envelope from a dispatch;
   * CSIP-AUS adds no separate flag for it.
   *
   * <p>{@link #NONE} is deliberately not envelope-only: a terminal retransmission's meaning lives
   * in the status, and it still has to close der_dispatch.
   */
  public boolean isEnvelopeOnly() {
    return targetActivePowerW == null && (importLimitW != null || exportLimitW != null);
  }
}
