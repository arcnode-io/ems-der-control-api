package io.arcnode.dercontrol.topology.dto;

/**
 * MVP-scoped slice of ems-device-api's DTM {@code Device} shape — only the field {@code
 * TopologyClient} needs to resolve a target by template. {@code device_id} isn't a field here
 * because the DTM's {@code devices} map is already keyed by it.
 */
public record TopologyDevice(String template) {}
