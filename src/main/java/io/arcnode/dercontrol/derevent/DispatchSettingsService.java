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
   * Sets the site's dispatch policy, upserting the singleton row.
   *
   * @param mode the mode to set
   * @return the mode just set
   */
  @Transactional
  public DispatchMode setMode(DispatchMode mode) {
    repository.save(new DispatchSettings(mode));
    return mode;
  }
}
