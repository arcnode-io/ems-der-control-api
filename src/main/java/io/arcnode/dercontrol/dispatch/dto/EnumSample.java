package io.arcnode.dercontrol.dispatch.dto;

/**
 * Canonical arcnode enum measurement payload (system_adr §13). Serialized as {@code {"ts": "...",
 * "value": <label>}} — {@code value} is the template's {@code values:} label, never the raw code.
 *
 * @param ts RFC3339 / ISO-8601 UTC timestamp with trailing {@code Z}
 * @param value the enum label
 */
public record EnumSample(String ts, String value) {}
