package io.arcnode.dercontrol.derevent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A DERControl event received from a utility / aggregator, persisted verbatim plus the normalized
 * fields the dispatch publisher needs. JPA needs a mutable class, so this is not a record.
 *
 * <p>Keyed for lookup by {@code mrid} (unique). A re-transmitted event (status change,
 * cancellation) updates the existing row via the setters — see {@code DerEventService}.
 */
@Entity
@Table(name = "der_event")
public class DerEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, updatable = false)
  private String mrid;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DerControlStatus status;

  @Column(nullable = false)
  private Instant intervalStart;

  @Column(nullable = false)
  private long durationSeconds;

  /** opModTargetW — absent when the DERControlBase carries no real-power target. */
  @Column private @Nullable Double targetActivePowerW;

  /** opModEnergize — absent when not specified. */
  @Column private @Nullable Boolean energize;

  /** opModImpLimW — absent unless the utility sent envelope-mode control. */
  @Column private @Nullable Double importLimitW;

  /** opModExpLimW — absent unless the utility sent envelope-mode control. */
  @Column private @Nullable Double exportLimitW;

  /**
   * An operator's approve/reject decision (ADR-002 §16) — {@code null} means "no decision yet."
   * Auto mode never sets this; {@link #derEventState} treats null as "proceed" outside manual mode.
   */
  @Column private @Nullable Boolean approved;

  @Column(nullable = false, updatable = false)
  private Instant receivedAt;

  /** The original request body, kept for audit / replay. */
  @Column(columnDefinition = "text", nullable = false, updatable = false)
  private String rawPayload;

  /** LFDI of the client cert that submitted this event (IEEE 2030.5 §6.3.4) — audit trail. */
  @Column(nullable = false)
  private String submittedByLfdi;

  /** JPA-only. */
  protected DerEvent() {
    // JPA instantiates via reflection, never calls this directly
  }

  public DerEvent(
      String mrid,
      DerControlStatus status,
      Instant intervalStart,
      long durationSeconds,
      @Nullable Double targetActivePowerW,
      @Nullable Boolean energize,
      @Nullable Double importLimitW,
      @Nullable Double exportLimitW,
      String rawPayload,
      String submittedByLfdi) {
    this.mrid = mrid;
    this.status = status;
    this.intervalStart = intervalStart;
    this.durationSeconds = durationSeconds;
    this.targetActivePowerW = targetActivePowerW;
    this.energize = energize;
    this.importLimitW = importLimitW;
    this.exportLimitW = exportLimitW;
    this.rawPayload = rawPayload;
    this.submittedByLfdi = submittedByLfdi;
    this.receivedAt = Instant.now();
  }

  /**
   * Where this event sits in the dispatch pipeline (ADR-002 §16): utility withdrawal
   * (cancelled/superseded) always wins; explicit rejection is terminal; manual mode with no
   * decision yet is pending; otherwise ACTIVE requires both the utility's own status saying ACTIVE
   * and the interval being open — 2030.5 servers retransmit status=Active when an interval opens,
   * so wall-clock time alone can't be trusted to self-declare activeness.
   *
   * @param mode site dispatch policy (ADR-002 §16)
   * @param now wall-clock instant to compare against {@code interval.start}
   * @return the state to publish
   */
  public DerEventState derEventState(DispatchMode mode, Instant now) {
    if (status == DerControlStatus.CANCELLED || status == DerControlStatus.SUPERSEDED) {
      return DerEventState.IDLE;
    }
    if (Boolean.FALSE.equals(approved)) {
      return DerEventState.REJECTED;
    }
    if (mode == DispatchMode.MANUAL && approved == null) {
      return DerEventState.PENDING;
    }
    boolean intervalOpen = !now.isBefore(intervalStart);
    return status == DerControlStatus.ACTIVE && intervalOpen
        ? DerEventState.ACTIVE
        : DerEventState.ARMED;
  }

  /**
   * True when {@link #derEventState} resolves to {@link DerEventState#ACTIVE} — the {@code
   * event_active} channel. Post-policy: reflects approval and interval timing, not just the
   * utility's raw status field.
   *
   * @param mode site dispatch policy (ADR-002 §16)
   * @param now wall-clock instant to compare against {@code interval.start}
   * @return whether the event is actually in force right now
   */
  public boolean isActive(DispatchMode mode, Instant now) {
    return derEventState(mode, now) == DerEventState.ACTIVE;
  }

  public @Nullable Boolean getApproved() {
    return approved;
  }

  public void setApproved(@Nullable Boolean approved) {
    this.approved = approved;
  }

  public Long getId() {
    return id;
  }

  public String getMrid() {
    return mrid;
  }

  public DerControlStatus getStatus() {
    return status;
  }

  public void setStatus(DerControlStatus status) {
    this.status = status;
  }

  public Instant getIntervalStart() {
    return intervalStart;
  }

  public void setIntervalStart(Instant intervalStart) {
    this.intervalStart = intervalStart;
  }

  public long getDurationSeconds() {
    return durationSeconds;
  }

  public void setDurationSeconds(long durationSeconds) {
    this.durationSeconds = durationSeconds;
  }

  public @Nullable Double getTargetActivePowerW() {
    return targetActivePowerW;
  }

  public void setTargetActivePowerW(@Nullable Double targetActivePowerW) {
    this.targetActivePowerW = targetActivePowerW;
  }

  public @Nullable Boolean getEnergize() {
    return energize;
  }

  public void setEnergize(@Nullable Boolean energize) {
    this.energize = energize;
  }

  public @Nullable Double getImportLimitW() {
    return importLimitW;
  }

  public void setImportLimitW(@Nullable Double importLimitW) {
    this.importLimitW = importLimitW;
  }

  public @Nullable Double getExportLimitW() {
    return exportLimitW;
  }

  public void setExportLimitW(@Nullable Double exportLimitW) {
    this.exportLimitW = exportLimitW;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public String getRawPayload() {
    return rawPayload;
  }

  public String getSubmittedByLfdi() {
    return submittedByLfdi;
  }

  public void setSubmittedByLfdi(String submittedByLfdi) {
    this.submittedByLfdi = submittedByLfdi;
  }
}
