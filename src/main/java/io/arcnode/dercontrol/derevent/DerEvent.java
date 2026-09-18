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
      String rawPayload,
      String submittedByLfdi) {
    this.mrid = mrid;
    this.status = status;
    this.intervalStart = intervalStart;
    this.durationSeconds = durationSeconds;
    this.targetActivePowerW = targetActivePowerW;
    this.energize = energize;
    this.rawPayload = rawPayload;
    this.submittedByLfdi = submittedByLfdi;
    this.receivedAt = Instant.now();
  }

  /** True when the event is currently commanding the DER — the {@code event_active} channel. */
  public boolean isActive() {
    return status == DerControlStatus.ACTIVE;
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
