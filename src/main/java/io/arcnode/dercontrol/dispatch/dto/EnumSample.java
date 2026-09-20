package io.arcnode.dercontrol.dispatch.dto;

/**
 * Canonical arcnode enum measurement payload (system_adr §13). Serialized as {@code {"ts": "...",
 * "value": "<label>"}}. Used for labeled-state channels (unit slot {@code none}).
 *
 * @param ts RFC3339 / ISO-8601 UTC timestamp with trailing {@code Z}
 * @param value the current label
 */
public record EnumSample(String ts, String value) {}
