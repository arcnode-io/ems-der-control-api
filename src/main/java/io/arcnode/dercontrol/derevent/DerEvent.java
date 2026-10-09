package io.arcnode.dercontrol.derevent;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
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

  /** The DERControlBase modes carried — its columns live on this table. */
  @Embedded private @Nullable DerControlBase control;

  /**
   * An operator's approve/reject decision (ADR-002 §16) — {@code null} means "no decision yet."
   * Auto mode never sets this; {@link DerEventState#of} treats null as "proceed" outside manual
   * mode.
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

  /** Which of the utility's programs issued this event — what it is for, and its rank. */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private DerProgram program;

  protected DerEvent() {
    // JPA-only — instantiated by reflection
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
      String submittedByLfdi,
      DerProgram program) {
    this.mrid = mrid;
    this.status = status;
    this.intervalStart = intervalStart;
    this.durationSeconds = durationSeconds;
    this.control = new DerControlBase(targetActivePowerW, energize, importLimitW, exportLimitW);
    this.rawPayload = rawPayload;
    this.submittedByLfdi = submittedByLfdi;
    this.program = program;
    this.receivedAt = Instant.now();
  }

  /** The state to publish — {@link DerEventState#of} under this site's policy, right now. */
  public DerEventState derEventState(DispatchMode mode, Instant now) {
    return DerEventState.of(this, mode, now);
  }

  /**
   * True when {@link #derEventState} resolves to {@link DerEventState#ACTIVE} — the {@code
   * event_active} channel. Post-policy: reflects approval and interval timing, not just the
   * utility's raw status field.
   */
  public boolean isActive(DispatchMode mode, Instant now) {
    return derEventState(mode, now) == DerEventState.ACTIVE;
  }

  /**
   * True when the utility has this control in force, taking no account of operator policy.
   *
   * <p>Distinct from {@link #isActive} on purpose. Some DERControl modes are mandatory: an
   * operating envelope is the boundary a site must stay inside at all times and cannot decline, so
   * no site-side policy — manual mode, an operator's refusal — may gate it. Those modes are
   * published on this predicate. A setpoint asks a site to move power, which it may refuse, so that
   * is published on {@link #isActive} instead.
   */
  public boolean isMandatoryInForce(Instant now) {
    return status == DerControlStatus.ACTIVE && !now.isBefore(intervalStart);
  }

  /** See {@link DerControlBase#isEnvelopeOnly}. */
  public boolean isEnvelopeOnly() {
    return getControl().isEnvelopeOnly();
  }

  /** The modes carried; {@link DerControlBase#NONE} when the control carried none. */
  public DerControlBase getControl() {
    // Reason: Hibernate materialises an embeddable whose columns are all null as null, and a
    // terminal retransmission carries no modes at all — so "no modes" has to read as a value here.
    return control == null ? DerControlBase.NONE : control;
  }

  public void setControl(DerControlBase control) {
    this.control = control;
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

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public String getRawPayload() {
    return rawPayload;
  }

  public DerProgram getProgram() {
    return program;
  }

  public String getSubmittedByLfdi() {
    return submittedByLfdi;
  }

  public void setSubmittedByLfdi(String submittedByLfdi) {
    this.submittedByLfdi = submittedByLfdi;
  }
}
