package io.arcnode.dercontrol.derevent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-facing dispatch policy toggle (ADR-002 §16). Runtime-settable on purpose — see {@link
 * DispatchMode}'s Javadoc for why this isn't {@code cfg.yml}.
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

  @Operation(summary = "Set the site's dispatch policy (auto|manual)")
  @PutMapping("/{mode}")
  public DispatchMode setMode(@PathVariable DispatchMode mode) {
    return service.setMode(mode);
  }
}
