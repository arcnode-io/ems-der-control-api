package io.arcnode.dercontrol.derevent.dto;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * This service's internal form of an IEEE 2030.5 {@code DERControl} — the fields the dispatch
 * publisher needs: identity, lifecycle status, the event window, and the setpoint. Built only by
 * {@link io.arcnode.dercontrol.derevent.DerControlNotificationParser}, which is where an inbound
 * document is checked, so these components carry no bean-validation constraints of their own.
 *
 * @param mrid 2030.5 mRID — the event's stable identity
 * @param eventStatus current {@code EventStatus.currentStatus}
 * @param interval the event's scheduled window
 * @param derControlBase the commanded setpoint
 */
public record DerControlRequest(
    String mrid, DerControlStatus eventStatus, Interval interval, ControlBase derControlBase) {

  /**
   * @param start window start ({@code DateTimeInterval.start})
   * @param durationSeconds window length ({@code DateTimeInterval.duration})
   */
  public record Interval(Instant start, long durationSeconds) {}

  /**
   * {@code DERControlBase} subset. All fields optional — a status-only re-transmission (e.g.
   * Cancelled) may carry none of them, and envelope-mode control (import/export limit) travels in
   * the same payload as target-mode control (real power target) rather than a separate one.
   *
   * @param opModTargetW commanded real power target, watts, signed
   * @param opModEnergize commanded energize/connect state
   * @param opModImpLimW commanded import limit (envelope mode), watts
   * @param opModExpLimW commanded export limit (envelope mode), watts
   */
  public record ControlBase(
      @Nullable Double opModTargetW,
      @Nullable Boolean opModEnergize,
      @Nullable Double opModImpLimW,
      @Nullable Double opModExpLimW) {}
}
