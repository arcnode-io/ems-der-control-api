package io.arcnode.dercontrol.dispatch.dto;

/**
 * Payload for der_dispatch's {@code set_dispatch_mode} command. Carries the label rather than an
 * ordinal, matching how every enum measurement on this bus is published — one format in both
 * directions, so an operator screen reads and writes the same vocabulary.
 *
 * @param value the {@code DispatchMode} name, {@code AUTO} or {@code MANUAL}
 */
public record ModeCommand(String value) {}
