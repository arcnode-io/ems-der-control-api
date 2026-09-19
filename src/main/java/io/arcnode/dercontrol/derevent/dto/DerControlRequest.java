package io.arcnode.dercontrol.derevent.dto;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /der-events} body — MVP subset of IEEE 2030.5 {@code DERControl}. Only the fields the
 * dispatch publisher needs: identity, lifecycle status, the event window, and the setpoint.
 *
 * @param mrid 2030.5 mRID — the event's stable identity
 * @param eventStatus current {@code EventStatus.currentStatus}
 * @param interval the event's scheduled window
 * @param derControlBase the commanded setpoint
 */
public record DerControlRequest(
    @NotBlank String mrid,
    @NotNull DerControlStatus eventStatus,
    @NotNull @Valid Interval interval,
    @NotNull @Valid ControlBase derControlBase) {

  /**
   * @param start window start ({@code DateTimeInterval.start})
   * @param durationSeconds window length ({@code DateTimeInterval.duration})
   */
  public record Interval(@NotNull Instant start, @Positive long durationSeconds) {}

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
