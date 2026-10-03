package io.arcnode.dercontrol.derevent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * Singleton row (always {@code id=1}) holding the site's dispatch policy: its {@link DispatchMode}
 * and how much energy the operator is holding back from answering an operating envelope. Persisted
 * rather than configured because both are operator decisions that must flip without a redeploy —
 * see {@link DispatchMode}'s Javadoc.
 */
@Entity
@Table(name = "dispatch_settings")
public class DispatchSettings {

  /** Always 1 — there is exactly one dispatch policy per deployment, not per anything else. */
  public static final long SINGLETON_ID = 1L;

  @Id private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private DispatchMode mode;

  /**
   * Energy the operator is holding back from answering an operating envelope, in watt-hours.
   *
   * <p>Soft reserve: the effective floor is the greater of this and the supplier's own
   * warranty-derived floor, so this can tighten what the battery will spend but never relax it.
   * Nullable, and null reads as zero — rows written before this field existed carry null, and the
   * gateway's contract is that an absent value means no operator reserve. Holding energy back does
   * not relax the envelope, which is mandatory; the compute shed answers whatever the battery then
   * cannot.
   */
  @Column private @Nullable Double operatorReserveWh;

  /** JPA-only. */
  protected DispatchSettings() {
    // JPA instantiates via reflection, never calls this directly
  }

  public DispatchSettings(DispatchMode mode) {
    this.id = SINGLETON_ID;
    this.mode = mode;
  }

  public Long getId() {
    return id;
  }

  public DispatchMode getMode() {
    return mode;
  }

  public void setMode(DispatchMode mode) {
    this.mode = mode;
  }

  public @Nullable Double getOperatorReserveWh() {
    return operatorReserveWh;
  }

  public void setOperatorReserveWh(@Nullable Double operatorReserveWh) {
    this.operatorReserveWh = operatorReserveWh;
  }
}
