package io.arcnode.dercontrol.derevent;

/**
 * Site dispatch policy (ADR-002 §16) — whether an event needs an operator's explicit approve/reject
 * before it can go {@link DispatchState#ACTIVE}. Runtime-settable via {@link
 * DispatchSettingsController}, not {@code cfg.yml} — this is an operator trust posture that should
 * flip without a redeploy, not boot-time infra config.
 */
public enum DispatchMode {
  AUTO,
  MANUAL
}
