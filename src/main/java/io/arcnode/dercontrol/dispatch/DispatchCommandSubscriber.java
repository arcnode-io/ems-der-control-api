package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerEventService;
import io.arcnode.dercontrol.dispatch.dto.ApproveRejectCommand;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * Subscribes to der_dispatch's {@code approve_dispatch}/{@code reject_dispatch} commands (ADR-002
 * §16) and routes them to {@link DerEventService}. Both commands target the {@code event_active}
 * bool measurement (verbs {@code enable}/{@code disable}); the payload optionally carries an {@code
 * mrid} ({@link ApproveRejectCommand}) since the fixed {@code commands/{verb}/event_active/ none}
 * topic shape has no slot for one — absent, the service falls back to its own heuristic.
 */
@Component
public class DispatchCommandSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(DispatchCommandSubscriber.class);
  private static final String TOPIC = "sites/%s/devices/der_dispatch/commands/%s/event_active/none";

  private final MqttClient mqtt;
  private final Config config;
  private final DerEventService service;
  private final JsonMapper mapper;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public DispatchCommandSubscriber(
      MqttClient mqtt, Config config, DerEventService service, JsonMapper mapper) {
    this.mqtt = mqtt;
    this.config = config;
    this.service = service;
    this.mapper = mapper;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Subscribes both command topics on application startup — after {@link MqttConfig} has already
   * connected the shared client, so the broker session is ready.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    String approveTopic = TOPIC.formatted(config.siteId(), "enable");
    String rejectTopic = TOPIC.formatted(config.siteId(), "disable");

    // Reason: the single-topic MqttClient.subscribe(String, int, IMqttMessageListener) overload
    // recurses into itself and stack-overflows — a confirmed Paho 1.2.5 bug
    // (eclipse-paho/paho.mqtt.java#917/#863/#816). The array overload it's supposed to delegate to
    // is fine; same workaround as DispatchPublishIT's test subscriber.
    mqtt.subscribe(
        new MqttSubscription[] {
          new MqttSubscription(approveTopic, 1), new MqttSubscription(rejectTopic, 1)
        },
        new IMqttMessageListener[] {
          (topic, message) -> guard(message, service::approveCurrentPending, "approve_dispatch"),
          (topic, message) -> guard(message, service::rejectCurrentPending, "reject_dispatch")
        });
  }

  /**
   * Parses the payload and runs one command handler off the Paho delivery thread, logging rather
   * than propagating a failure — a malformed body is treated the same as any other failure to apply
   * the command, not a special case. Two reasons this can't run inline on the calling thread: an
   * exception thrown from an {@link IMqttMessageListener} callback would kill the broker's delivery
   * thread and silently stop future command deliveries; and the handler itself calls back into
   * {@link DispatchPublisher}, which publishes on this same {@code mqtt} client — a synchronous
   * publish issued from inside that client's own message-arrived callback silently stalls after the
   * first send (confirmed against a real broker: verified handling never got past the send that was
   * already-in-flight when the callback fired, no exception, no explanation in the Paho client
   * source — the fix is the same regardless of the exact internal cause: never do client work on
   * the client's own delivery thread).
   */
  private void guard(MqttMessage message, Consumer<String> action, String commandName) {
    executor.submit(
        () -> {
          try {
            action.accept(parseMrid(message));
          } catch (RuntimeException e) {
            LOG.error("failed to apply {} command", commandName, e);
          }
        });
  }

  private @Nullable String parseMrid(MqttMessage message) {
    byte[] payload = message.getPayload();
    if (payload.length == 0) {
      return null;
    }
    return mapper.readValue(payload, ApproveRejectCommand.class).mrid();
  }
}
