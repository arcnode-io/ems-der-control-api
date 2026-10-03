package io.arcnode.dercontrol.dispatch.dto;

/**
 * Payload for der_dispatch's {@code set_operator_reserve} command. Carries the system-wide sample
 * envelope's {@code value}; the {@code ts} rides along and is ignored, since the decision is the
 * message.
 *
 * @param value watt-hours the operator is holding back from answering the envelope
 */
public record ReserveCommand(Double value) {}
