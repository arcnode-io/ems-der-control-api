package io.arcnode.dercontrol.derevent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads/writes the site's current {@link DispatchMode} — see its Javadoc for why. */
@Service
public class DispatchSettingsService {

  private final DispatchSettingsRepository repository;

  public DispatchSettingsService(DispatchSettingsRepository repository) {
    this.repository = repository;
  }

  /**
   * The site's current dispatch policy. Defaults to {@link DispatchMode#AUTO} when nobody has ever
   * set one — no behavior change for a fresh deployment.
   *
   * @return the current mode
   */
  public DispatchMode currentMode() {
    return repository
        .findById(DispatchSettings.SINGLETON_ID)
        .map(DispatchSettings::getMode)
        .orElse(DispatchMode.AUTO);
  }

  /**
   * Energy the operator is holding back from answering an operating envelope, in watt-hours.
   * Defaults to none, and an absent value reads the same way — which matches the gateway's own
   * contract, so a fresh deployment and a row written before the field existed agree.
   *
   * @return the operator's reserve in watt-hours, zero when none is set
   */
  public double operatorReserveWh() {
    return repository
        .findById(DispatchSettings.SINGLETON_ID)
        .map(DispatchSettings::getOperatorReserveWh)
        .map(Double::doubleValue)
        .orElse(0.0);
  }

  /**
   * Sets the site's dispatch policy, upserting the singleton row.
   *
   * @param mode the mode to set
   * @return the mode just set
   */
  @Transactional
  public DispatchMode setMode(DispatchMode mode) {
    DispatchSettings settings = settings();
    settings.setMode(mode);
    repository.save(settings);
    return mode;
  }

  /**
   * Sets how much energy the operator holds back from answering the envelope.
   *
   * <p>Not validated against installed capacity on purpose. Refusing a reserve larger than today's
   * capacity would be wrong the moment racks are added at commissioning, and the gateway takes the
   * greater of this and the supplier floor — so an oversized value is merely ineffective beyond
   * "never discharge", never dangerous. A negative one is meaningless, so it clamps to none.
   *
   * @param reserveWh watt-hours to hold back
   * @return the value actually stored
   */
  @Transactional
  public double setOperatorReserveWh(double reserveWh) {
    double stored = Math.max(0.0, reserveWh);
    DispatchSettings settings = settings();
    settings.setOperatorReserveWh(stored);
    repository.save(settings);
    return stored;
  }

  /**
   * The singleton row, created on first use.
   *
   * <p>Reason: load and mutate rather than construct. Both fields live on one row, so saving a
   * freshly built row to change one of them silently resets the other — an operator's reserve would
   * be wiped by someone flipping the dispatch mode.
   */
  private DispatchSettings settings() {
    return repository
        .findById(DispatchSettings.SINGLETON_ID)
        .orElseGet(() -> new DispatchSettings(DispatchMode.AUTO));
  }
}
