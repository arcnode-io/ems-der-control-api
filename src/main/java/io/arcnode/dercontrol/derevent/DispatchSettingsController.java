package io.arcnode.dercontrol.derevent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the site's dispatch policy (ADR-002 §16), for debugging.
 *
 * <p>Setting it lives on the broker ({@link
 * io.arcnode.dercontrol.dispatch.DispatchModeSubscriber}), not here: this service terminates no
 * authentication, so an HTTP write would let anyone who can reach it flip the site's trust posture.
 * Runtime-settable on purpose either way — see {@link DispatchMode}'s Javadoc for why this isn't
 * {@code cfg.yml}.
 */
@Tag(name = "dispatch-settings")
@RestController
@RequestMapping("/dispatch-settings")
public class DispatchSettingsController {

  private final DispatchSettingsService service;

  public DispatchSettingsController(DispatchSettingsService service) {
    this.service = service;
  }

  @Operation(summary = "Get the site's current dispatch policy (auto|manual)")
  @GetMapping
  public DispatchMode getMode() {
    return service.currentMode();
  }
}
