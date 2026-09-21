package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.dispatch.dto.BooleanSample;
import io.arcnode.dercontrol.dispatch.dto.FloatSample;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Phase III non-delivery detection. Subscribes to der_dispatch's own {@code target_active_power}
 * and {@code event_active} (both already published by {@link DispatchPublisher}) plus a new {@code
 * actual_active_power} (published by the gateway — the site-level real delivered power) and, once a
 * persistent gap survives {@link #SHORTFALL_THRESHOLD_TICKS} consecutive samples, publishes {@code
 * dispatch_shortfall} (under-delivery) or {@code dispatch_overdelivery} (over-delivery) — tracked
 * and published independently, not one signed signal, since over-delivery (e.g. exceeding an export
 * cap) can be the more safety-relevant direction and shouldn't be buried under "shortfall." Both
 * are orthogonal to {@code der_event_state}: a physical delivery gap is not a policy/ authorization
 * concern. Needs no topology awareness: every input is a fixed, well-known channel on the virtual
 * der_dispatch device.
 */
@Component
public class DeliveryShortfallMonitor {

  private static final Logger LOG = LoggerFactory.getLogger(DeliveryShortfallMonitor.class);
  private static final String TARGET_TOPIC =
      "sites/%s/devices/der_dispatch/measurements/target_active_power/watts";
  private static final String EVENT_ACTIVE_TOPIC =
      "sites/%s/devices/der_dispatch/measurements/event_active/none";
  private static final String ACTUAL_TOPIC =
      "sites/%s/devices/der_dispatch/measurements/actual_active_power/watts";

  // Reason: MVP placeholders, not spec'd by anyone yet — tune once real BESS delivery behavior
  // is observed. Tolerance is absolute watts rather than a percentage to avoid flapping near a
  // near-zero target; threshold ticks double as hysteresis against a single noisy sample.
  private static final double SHORTFALL_TOLERANCE_WATTS = 500.0;
  private static final int SHORTFALL_THRESHOLD_TICKS = 3;

  private final MqttClient mqtt;
  private final Config config;
  private final JsonMapper mapper;
  private final DispatchPublisher publisher;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private final AtomicReference<@Nullable Double> lastTarget = new AtomicReference<>();
  private final AtomicBoolean eventActive = new AtomicBoolean();
  private final AtomicInteger consecutiveShortfalls = new AtomicInteger();
  private final AtomicBoolean currentlyShortfall = new AtomicBoolean();
  private final AtomicInteger consecutiveOverdeliveries = new AtomicInteger();
  private final AtomicBoolean currentlyOverdelivering = new AtomicBoolean();

  public DeliveryShortfallMonitor(
      MqttClient mqtt, Config config, JsonMapper mapper, DispatchPublisher publisher) {
    this.mqtt = mqtt;
    this.config = config;
    this.mapper = mapper;
    this.publisher = publisher;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Subscribes all three input channels on application startup, after {@link MqttConfig}. */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    String targetTopic = TARGET_TOPIC.formatted(config.siteId());
    String eventActiveTopic = EVENT_ACTIVE_TOPIC.formatted(config.siteId());
    String actualTopic = ACTUAL_TOPIC.formatted(config.siteId());

    // Reason: same Paho array-overload workaround as DispatchCommandSubscriber — the single-topic
    // subscribe(String, int, IMqttMessageListener) overload recurses and stack-overflows.
    mqtt.subscribe(
        new MqttSubscription[] {
          new MqttSubscription(targetTopic, 1),
          new MqttSubscription(eventActiveTopic, 1),
          new MqttSubscription(actualTopic, 1)
        },
        new IMqttMessageListener[] {
          (topic, message) -> guard(() -> handleTarget(message)),
          (topic, message) -> guard(() -> handleEventActive(message)),
          (topic, message) -> guard(() -> handleActual(message))
        });
  }

  private void handleTarget(MqttMessage message) {
    lastTarget.set(mapper.readValue(message.getPayload(), FloatSample.class).value());
  }

  private void handleEventActive(MqttMessage message) {
    boolean active = mapper.readValue(message.getPayload(), BooleanSample.class).value();
    eventActive.set(active);
    if (!active) {
      resetShortfall();
      resetOverdelivery();
    }
  }

  private void handleActual(MqttMessage message) {
    double actual = mapper.readValue(message.getPayload(), FloatSample.class).value();
    Double target = lastTarget.get();
    if (!eventActive.get() || target == null) {
      resetShortfall();
      resetOverdelivery();
      return;
    }
    double gap = actual - target;
    if (gap < -SHORTFALL_TOLERANCE_WATTS) {
      resetOverdelivery();
      if (consecutiveShortfalls.incrementAndGet() >= SHORTFALL_THRESHOLD_TICKS
          && currentlyShortfall.compareAndSet(false, true)) {
        publisher.publishShortfall(true);
      }
    } else if (gap > SHORTFALL_TOLERANCE_WATTS) {
      resetShortfall();
      if (consecutiveOverdeliveries.incrementAndGet() >= SHORTFALL_THRESHOLD_TICKS
          && currentlyOverdelivering.compareAndSet(false, true)) {
        publisher.publishOverdelivery(true);
      }
    } else {
      resetShortfall();
      resetOverdelivery();
    }
  }

  private void resetShortfall() {
    consecutiveShortfalls.set(0);
    if (currentlyShortfall.compareAndSet(true, false)) {
      publisher.publishShortfall(false);
    }
  }

  private void resetOverdelivery() {
    consecutiveOverdeliveries.set(0);
    if (currentlyOverdelivering.compareAndSet(true, false)) {
      publisher.publishOverdelivery(false);
    }
  }

  /**
   * Runs one handler off the Paho delivery thread, logging rather than propagating a failure — same
   * reasoning as {@link DispatchCommandSubscriber#guard}: an exception thrown from the callback
   * would kill the broker's delivery thread, and {@link DispatchPublisher#publish} called
   * synchronously from inside it would stall.
   */
  private void guard(Runnable action) {
    executor.submit(
        () -> {
          try {
            action.run();
          } catch (RuntimeException e) {
            LOG.error("failed to process delivery-shortfall input", e);
          }
        });
  }
}
