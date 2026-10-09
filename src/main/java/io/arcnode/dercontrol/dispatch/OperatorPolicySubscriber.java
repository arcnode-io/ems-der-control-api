package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.dispatch.dto.ModeCommand;
import io.arcnode.dercontrol.dispatch.dto.ReserveCommand;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Carries the site's operator-settable dispatch policy over the broker in both directions: the
 * operator writes a {@code set_*} command and reads the value back off a retained measurement.
 *
 * <p>Two policies today. {@code dispatch_mode} (ADR-002 §16) decides whether a dispatch needs an
 * explicit approval. {@code operator_reserve} decides how much energy is held back from answering
 * an operating envelope — a soft reserve on top of the supplier's own warranty floor, which it can
 * tighten but never relax. Holding energy back does not relax the envelope, which is mandatory; the
 * compute shed answers whatever the battery then cannot.
 *
 * <p>On the broker rather than an HTTP endpoint because this service terminates no authentication
 * of its own, so an HTTP route would let anyone who can reach the page flip the site's trust
 * posture. The broker already authenticates the operator and already carries this device's
 * approve/reject commands, so there is no new auth surface here.
 */
@Component
public class OperatorPolicySubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(OperatorPolicySubscriber.class);
  private static final String MODE_COMMAND =
      "sites/%s/devices/der_dispatch/commands/set/dispatch_mode/none";
  private static final String RESERVE_COMMAND =
      "sites/%s/devices/der_dispatch/commands/set/operator_reserve/watt_hours";

  private final MqttClient mqtt;
  private final Config config;
  private final DispatchSettingsService settings;
  private final JsonMapper mapper;
  private final SiteStatusPublisher publisher;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public OperatorPolicySubscriber(
      MqttClient mqtt,
      Config config,
      DispatchSettingsService settings,
      JsonMapper mapper,
      SiteStatusPublisher publisher) {
    this.mqtt = mqtt;
    this.config = config;
    this.settings = settings;
    this.mapper = mapper;
    this.publisher = publisher;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /**
   * Subscribes the command topics and restates the retained mode and reserve.
   *
   * <p>The republish is here rather than only on change because the retained value is the only way
   * a screen learns the posture, and a broker that dropped its session dropped that too — so every
   * (re)connect has to restate it. Called by {@link MqttReconnectHandler}, the one owner of
   * subscription lifecycle.
   *
   * @throws MqttException if the subscribe itself fails
   */
  public void subscribe() throws MqttException {
    mqtt.subscribe(
        new MqttSubscription[] {
          new MqttSubscription(MODE_COMMAND.formatted(config.siteId()), 1),
          new MqttSubscription(RESERVE_COMMAND.formatted(config.siteId()), 1)
        },
        new IMqttMessageListener[] {
          (topic, message) -> applyMode(message), (topic, message) -> applyReserve(message)
        });
    restatePosture();
  }

  /**
   * Restates the retained mode and reserve off the calling thread.
   *
   * <p>Reason for the executor: {@link MqttReconnectHandler} calls {@code subscribe()} from {@code
   * connectComplete}, which runs on Paho's callback thread, and {@code MqttClient.publish} blocks
   * until the broker's ack is processed by that very thread. Publishing inline deadlocks the
   * reconnect — the subscription lands, nothing after it ever runs, and the connection is never
   * recovered. Same executor and same reason as the command handlers.
   */
  private void restatePosture() {
    executor.submit(
        () -> {
          try {
            publisher.publishDispatchMode(settings.currentMode());
            publisher.publishOperatorReserve(settings.operatorReserveWh());
          } catch (RuntimeException e) {
            LOG.error("failed to restate operator policy on the bus", e);
          }
        });
  }

  /**
   * Applies one mode command off the Paho delivery thread — same two reasons as {@link
   * DispatchCommandSubscriber#guard}: an exception thrown back into the callback would kill the
   * broker's delivery thread, and this handler publishes on the very client whose callback it is.
   */
  private void applyMode(MqttMessage message) {
    executor.submit(
        () -> {
          try {
            String label = mapper.readValue(message.getPayload(), ModeCommand.class).value();
            publisher.publishDispatchMode(settings.setMode(DispatchMode.valueOf(label)));
            LOG.info("🎛 Dispatch mode set to {} by operator command", label);
          } catch (RuntimeException e) {
            LOG.error("failed to apply set_dispatch_mode command", e);
          }
        });
  }

  private void applyReserve(MqttMessage message) {
    executor.submit(
        () -> {
          try {
            Double asked = mapper.readValue(message.getPayload(), ReserveCommand.class).value();
            double stored = settings.setOperatorReserveWh(asked);
            publisher.publishOperatorReserve(stored);
            if (LOG.isInfoEnabled()) {
              LOG.info(
                  "🔋 Operator reserve set to {}Wh — held back from answering the envelope, the"
                      + " compute shed answers whatever the battery then cannot",
                  stored);
            }
          } catch (RuntimeException e) {
            LOG.error("failed to apply set_operator_reserve command", e);
          }
        });
  }
}
