package io.arcnode.dercontrol.topology.dto;

import java.util.Map;

/** MVP-scoped mirror of ems-device-api's {@code GET /topology} body — devices, keyed by id. */
public record TopologyResponse(Map<String, TopologyDevice> devices) {}
