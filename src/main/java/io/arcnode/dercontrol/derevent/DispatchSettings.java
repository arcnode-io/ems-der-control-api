package io.arcnode.dercontrol.derevent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Singleton row (always {@code id=1}) holding the site's current {@link DispatchMode}.
 * Runtime-settable via {@link DispatchSettingsController} — see {@link DispatchMode}'s Javadoc for
 * why this is a persisted row, not a {@code cfg.yml} field.
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
}
