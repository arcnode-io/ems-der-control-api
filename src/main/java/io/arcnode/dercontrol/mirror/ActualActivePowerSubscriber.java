package io.arcnode.dercontrol.mirror;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds der_dispatch's latest {@code actual_active_power} for the {@code mirror} package's own use
 * — a separate subscription from {@link io.arcnode.dercontrol.dispatch.DeliveryShortfallMonitor}'s
 * (same topic, different concern: mirror reporting vs. shortfall detection). Feeds {@link
 * MirrorUsagePointFactory} the real measured value the utility reads back.
 */
@Component
public class ActualActivePowerSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(ActualActivePowerSubscriber.class);
  private static final String TOPIC =
      "sites/%s/devices/der_dispatch/measurements/actual_active_power/watts";

  private final MqttClient mqtt;
  private final Config config;
  private final JsonMapper mapper;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicReference<@Nullable Double> latestActiveWatts = new AtomicReference<>();

  public ActualActivePowerSubscriber(MqttClient mqtt, Config config, JsonMapper mapper) {
    this.mqtt = mqtt;
    this.config = config;
    this.mapper = mapper;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Subscribes on application startup, after {@link io.arcnode.dercontrol.MqttConfig}. */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    String topic = TOPIC.formatted(config.siteId());

    // Reason: same Paho array-overload workaround as every other subscriber in this service — the
    // single-topic overload recurses and stack-overflows (eclipse-paho/paho.mqtt.java#917).
    mqtt.subscribe(
        new MqttSubscription[] {new MqttSubscription(topic, 1)},
        new IMqttMessageListener[] {(t, message) -> guard(message)});
  }

  /** Latest known actual active power (watts), or {@code null} before the first message arrives. */
  public @Nullable Double currentActiveWatts() {
    return latestActiveWatts.get();
  }

  private void guard(MqttMessage message) {
    executor.submit(
        () -> {
          try {
            latestActiveWatts.set(
                mapper.readValue(message.getPayload(), FloatSample.class).value());
          } catch (RuntimeException e) {
            LOG.error("failed to process actual_active_power message", e);
          }
        });
  }
}
