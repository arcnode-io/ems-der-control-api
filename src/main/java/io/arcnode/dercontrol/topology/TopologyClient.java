package io.arcnode.dercontrol.topology;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.topology.dto.TopologyResponse;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Resolves the site's real BESS asset from ems-device-api's topology, so der-control-api can
 * command it once an event goes {@code ACTIVE} (ADR-002 §16).
 *
 * <p>{@code bess_rack} is the actuatable leaf (a real device with a real Modbus TCP binding on its
 * {@code set_active_power} command) — {@code bess_module} one level up is a module-kind rollup with
 * no binding of its own, aggregating however many racks are provisioned under it.
 */
@Component
public class TopologyClient {

  private static final Logger LOG = LoggerFactory.getLogger(TopologyClient.class);
  private static final String BESS_RACK_TEMPLATE = "bess_rack";

  private final RestClient restClient;

  public TopologyClient(RestClient.Builder builder, Config config) {
    this.restClient = builder.baseUrl(config.deviceApiUrl()).build();
  }

  /**
   * The first {@code bess_rack} device in the site's topology. MVP: a site with more than one needs
   * a real setpoint-allocation design (splitting one target across several racks) that doesn't
   * exist yet — this only ever targets one.
   *
   * @return the device_id to command, or empty if none is provisioned yet or device-api is
   *     unreachable — a missing/unready topology shouldn't fail the DER event itself
   */
  public Optional<String> findBessRackDeviceId() {
    try {
      TopologyResponse response =
          restClient.get().uri("/topology").retrieve().body(TopologyResponse.class);
      if (response == null) {
        return Optional.empty();
      }
      return response.devices().entrySet().stream()
          .filter(entry -> BESS_RACK_TEMPLATE.equals(entry.getValue().template()))
          .map(Map.Entry::getKey)
          .findFirst();
    } catch (RestClientException e) {
      LOG.warn("topology lookup failed; no bess_rack target resolved", e);
      return Optional.empty();
    }
  }
}
