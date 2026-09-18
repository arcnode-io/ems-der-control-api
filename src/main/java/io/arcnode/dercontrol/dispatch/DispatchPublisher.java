package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import java.time.Clock;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a {@link DerEvent} into canonical arcnode measurement samples and publishes them to
 * the deployment broker on the {@code der_dispatch} device's channels (system_adr §12/§13/§18).
 *
 * <p>Every ingested event publishes {@code event_active}. {@code target_active_power} and {@code
 * energize_enabled} publish only when the DERControlBase carried that control.
 */
@Component
public class DispatchPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(DispatchPublisher.class);
  private static final String DEVICE_ID = "der_dispatch";
  private static final String TOPIC = "sites/%s/devices/" + DEVICE_ID + "/measurements/%s/%s";
  private static final int QOS = 0;
  private static final boolean RETAIN = true;

  private final MqttClient mqtt;
  private final JsonMapper mapper;
  private final Config config;
  private final Clock clock;

  public DispatchPublisher(MqttClient mqtt, JsonMapper mapper, Config config, Clock clock) {
    this.mqtt = mqtt;
    this.mapper = mapper;
    this.config = config;
    this.clock = clock;
  }

  /** Publish the setpoint + status channels for one event. */
  public void publish(DerEvent event) {
    String ts = clock.instant().toString();

    Double targetActivePowerW = event.getTargetActivePowerW();
    if (targetActivePowerW != null) {
      send("target_active_power", "watts", new FloatSample(ts, targetActivePowerW));
    }
    send("event_active", "none", new BooleanSample(ts, event.isActive()));
    Boolean energize = event.getEnergize();
    if (energize != null) {
      send("energize_enabled", "none", new BooleanSample(ts, energize));
    }

    if (LOG.isInfoEnabled()) {
      LOG.info("published dispatch for mRID {} (status {})", event.getMrid(), event.getStatus());
    }
  }

  private void send(String measurement, String unit, Object sample) {
    String topic = TOPIC.formatted(config.siteId(), measurement, unit);
    try {
      mqtt.publish(topic, mapper.writeValueAsBytes(sample), QOS, RETAIN);
    } catch (JacksonException | MqttException e) {
      // Reason: a failed publish means the dispatch never reached the bus — fail loud so the
      // ingest transaction rolls back rather than silently accepting a lost setpoint.
      throw new IllegalStateException("failed to publish " + topic, e);
    }
  }
}
