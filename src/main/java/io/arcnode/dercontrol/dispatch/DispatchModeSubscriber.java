package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.dispatch.dto.ModeCommand;
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
 * Carries the site's dispatch policy (ADR-002 §16) over the broker in both directions: the operator
 * sets it with {@code set_dispatch_mode} and reads it back off the retained {@code dispatch_mode}
 * measurement.
 *
 * <p>On the broker rather than an HTTP endpoint because this service terminates no authentication
 * of its own, so an HTTP route would let anyone who can reach the page flip the site's trust
 * posture. The broker already authenticates the operator and already carries this device's
 * approve/reject commands, so there is no new auth surface here.
 */
@Component
public class DispatchModeSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(DispatchModeSubscriber.class);
  private static final String COMMAND_TOPIC =
      "sites/%s/devices/der_dispatch/commands/set/dispatch_mode/none";

  private final MqttClient mqtt;
  private final Config config;
  private final DispatchSettingsService settings;
  private final JsonMapper mapper;
  private final DispatchPublisher publisher;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public DispatchModeSubscriber(
      MqttClient mqtt,
      Config config,
      DispatchSettingsService settings,
      JsonMapper mapper,
      DispatchPublisher publisher) {
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
   * Subscribes the command topic and republishes the current mode.
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
        new MqttSubscription[] {new MqttSubscription(COMMAND_TOPIC.formatted(config.siteId()), 1)},
        new IMqttMessageListener[] {(topic, message) -> apply(message)});
    publisher.publishDispatchMode(settings.currentMode());
  }

  /**
   * Applies one mode command off the Paho delivery thread — same two reasons as {@link
   * DispatchCommandSubscriber#guard}: an exception thrown back into the callback would kill the
   * broker's delivery thread, and this handler publishes on the very client whose callback it is.
   */
  private void apply(MqttMessage message) {
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
}
