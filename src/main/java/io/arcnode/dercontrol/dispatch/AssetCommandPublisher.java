package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import io.arcnode.dercontrol.topology.TopologyClient;
import java.time.Clock;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commands the site's real BESS asset once a DER event is actually {@code ACTIVE} (ADR-002 §16) —
 * the step that turns der_dispatch's own setpoint channel into a real instruction on the bus.
 */
@Component
public class AssetCommandPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(AssetCommandPublisher.class);
  private static final String TOPIC = "sites/%s/devices/%s/commands/set/active_power/watts";
  private static final int QOS = 1;
  private static final boolean RETAIN = false;

  private final MqttClient mqtt;
  private final JsonMapper mapper;
  private final Config config;
  private final Clock clock;
  private final TopologyClient topologyClient;

  public AssetCommandPublisher(
      MqttClient mqtt,
      JsonMapper mapper,
      Config config,
      Clock clock,
      TopologyClient topologyClient) {
    this.mqtt = mqtt;
    this.mapper = mapper;
    this.config = config;
    this.clock = clock;
    this.topologyClient = topologyClient;
  }

  /**
   * Resolves the site's bess_rack via topology and commands it to {@code activePowerW}. A no-op,
   * logged not thrown, when topology hasn't got a bess_rack yet — that's an ops/provisioning state,
   * not a bug in this event's own dispatch.
   */
  public void publishSetpoint(double activePowerW) {
    topologyClient
        .findBessRackDeviceId()
        .ifPresentOrElse(
            deviceId -> send(deviceId, activePowerW),
            () -> LOG.warn("no bess_rack in topology; setpoint not sent to any real asset"));
  }

  private void send(String deviceId, double activePowerW) {
    String topic = TOPIC.formatted(config.siteId(), deviceId);
    FloatSample sample = new FloatSample(clock.instant().toString(), activePowerW);
    try {
      mqtt.publish(topic, mapper.writeValueAsBytes(sample), QOS, RETAIN);
    } catch (JacksonException | MqttException e) {
      throw new IllegalStateException("failed to publish asset command " + topic, e);
    }
  }
}
