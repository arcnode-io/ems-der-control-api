package io.arcnode.dercontrol.eventlog;

import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEventState;
import io.arcnode.dercontrol.derevent.DerProgram;
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
 * One thing that happened on this site, as recorded by the code path that made it happen. Append
 * only: rows are never updated, and nothing here is acknowledged — that is an alarm's lifecycle,
 * which has its own store. JPA needs a mutable class, so this is not a record.
 */
@Entity
@Table(name = "event_log")
public class EventLog {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, updatable = false)
  private Instant occurredAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, updatable = false)
  private EventType type;

  /** The mRID for a DER event; the setting's name for an operator setting. */
  @Column(nullable = false, updatable = false)
  private String subject;

  /** Who did it: the submitting client's LFDI, an operator role, or null for the site itself. */
  @Column(updatable = false)
  private @Nullable String actor;

  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private @Nullable DerProgram program;

  /** The utility's own status, on the rows that carry one. */
  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private @Nullable DerControlStatus status;

  /** The site's resolved posture, on {@link EventType#DER_EVENT_STATE} rows. */
  @Enumerated(EnumType.STRING)
  @Column(updatable = false)
  private @Nullable DerEventState state;

  /** A number the event carries, such as a reserve in watt-hours. */
  @Column(updatable = false)
  private @Nullable Double value;

  /** A short label the event carries, such as the dispatch mode set. */
  @Column(updatable = false)
  private @Nullable String detail;

  protected EventLog() {
    // JPA-only — instantiated by reflection
  }

  public EventLog(
      Instant occurredAt,
      EventType type,
      String subject,
      @Nullable String actor,
      @Nullable DerProgram program,
      @Nullable DerControlStatus status,
      @Nullable DerEventState state,
      @Nullable Double value,
      @Nullable String detail) {
    this.occurredAt = occurredAt;
    this.type = type;
    this.subject = subject;
    this.actor = actor;
    this.program = program;
    this.status = status;
    this.state = state;
    this.value = value;
    this.detail = detail;
  }

  public Long getId() {
    return id;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public EventType getType() {
    return type;
  }

  public String getSubject() {
    return subject;
  }

  public @Nullable String getActor() {
    return actor;
  }

  public @Nullable DerProgram getProgram() {
    return program;
  }

  public @Nullable DerControlStatus getStatus() {
    return status;
  }

  public @Nullable DerEventState getState() {
    return state;
  }

  public @Nullable Double getValue() {
    return value;
  }

  public @Nullable String getDetail() {
    return detail;
  }
}
