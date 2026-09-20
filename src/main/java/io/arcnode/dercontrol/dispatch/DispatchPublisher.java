package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.EnumSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import java.time.Clock;
import java.time.Instant;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a {@link DerEvent} into canonical arcnode measurement samples and publishes them to
 * the deployment broker on the {@code der_dispatch} device's channels (system_adr §12/§13/§18),
 * plus the site's {@code operating_envelope} device when the same DERControlBase payload carried
 * envelope-mode limits (opModImpLimW/opModExpLimW) alongside or instead of a target-mode setpoint.
 *
 * <p>Every ingested event publishes {@code event_active} and {@code dispatch_state} (ADR-002 §16 —
 * reflects the site's current {@link DispatchMode}, not just the utility's raw status). {@code
 * target_active_power}, {@code energize_enabled}, {@code import_limit}, and {@code export_limit}
 * each publish only when the DERControlBase carried that control.
 */
@Component
public class DispatchPublisher {

  private static final Logger LOG = LoggerFactory.getLogger(DispatchPublisher.class);
  private static final String DEVICE_ID = "der_dispatch";
  private static final String ENVELOPE_DEVICE_ID = "operating_envelope";
  private static final String TOPIC = "sites/%s/devices/%s/measurements/%s/%s";
  private static final int QOS = 0;
  private static final boolean RETAIN = true;

  private final MqttClient mqtt;
  private final JsonMapper mapper;
  private final Config config;
  private final Clock clock;
  private final DispatchSettingsService dispatchSettings;

  public DispatchPublisher(
      MqttClient mqtt,
      JsonMapper mapper,
      Config config,
      Clock clock,
      DispatchSettingsService dispatchSettings) {
    this.mqtt = mqtt;
    this.mapper = mapper;
    this.config = config;
    this.clock = clock;
    this.dispatchSettings = dispatchSettings;
  }

  /** Publish the setpoint + status + envelope channels for one event. */
  public void publish(DerEvent event) {
    String ts = clock.instant().toString();
    Instant now = clock.instant();
    DispatchMode mode = dispatchSettings.currentMode();

    Double targetActivePowerW = event.getTargetActivePowerW();
    if (targetActivePowerW != null) {
      send(DEVICE_ID, "target_active_power", "watts", new FloatSample(ts, targetActivePowerW));
    }
    send(DEVICE_ID, "event_active", "none", new BooleanSample(ts, event.isActive(mode, now)));
    send(
        DEVICE_ID,
        "dispatch_state",
        "none",
        new EnumSample(ts, event.dispatchState(mode, now).name()));
    Boolean energize = event.getEnergize();
    if (energize != null) {
      send(DEVICE_ID, "energize_enabled", "none", new BooleanSample(ts, energize));
    }

    Double importLimitW = event.getImportLimitW();
    if (importLimitW != null) {
      send(ENVELOPE_DEVICE_ID, "import_limit", "watts", new FloatSample(ts, importLimitW));
    }
    Double exportLimitW = event.getExportLimitW();
    if (exportLimitW != null) {
      send(ENVELOPE_DEVICE_ID, "export_limit", "watts", new FloatSample(ts, exportLimitW));
    }

    if (LOG.isInfoEnabled()) {
      LOG.info("published dispatch for mRID {} (status {})", event.getMrid(), event.getStatus());
    }
  }

  private void send(String deviceId, String measurement, String unit, Object sample) {
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
