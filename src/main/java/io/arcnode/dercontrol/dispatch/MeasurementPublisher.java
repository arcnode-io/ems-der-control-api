package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Puts one canonical arcnode measurement sample on the deployment broker, retained, at {@code
 * sites/{site}/devices/{device}/measurements/{name}/{unit}}. The one place the topic shape, QoS and
 * retain flag are decided; every publisher in this package goes through it.
 */
@Component
public class MeasurementPublisher {

  private static final String TOPIC = "sites/%s/devices/%s/measurements/%s/%s";
  private static final int QOS = 0;
  private static final boolean RETAIN = true;

  private final MqttClient mqtt;
  private final JsonMapper mapper;
  private final Config config;

  public MeasurementPublisher(MqttClient mqtt, JsonMapper mapper, Config config) {
    this.mqtt = mqtt;
    this.mapper = mapper;
    this.config = config;
  }

  /**
   * @param deviceId the device the channel belongs to (e.g. {@code der_dispatch})
   * @param measurement the channel name
   * @param unit the channel's unit segment ({@code watts}, {@code none}, ...)
   * @param sample a {@code *Sample} record — serialised as the payload
   * @throws IllegalStateException when the sample never reached the bus
   */
  public void publish(String deviceId, String measurement, String unit, Object sample) {
    String topic = TOPIC.formatted(config.siteId(), deviceId, measurement, unit);
    try {
      mqtt.publish(topic, mapper.writeValueAsBytes(sample), QOS, RETAIN);
    } catch (JacksonException | MqttException e) {
      // Reason: a failed publish means the dispatch never reached the bus — fail loud so the
      // ingest transaction rolls back rather than silently accepting a lost setpoint.
      throw new IllegalStateException("failed to publish " + topic, e);
    }
  }
}
