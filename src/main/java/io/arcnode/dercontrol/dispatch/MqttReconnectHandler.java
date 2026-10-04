package io.arcnode.dercontrol.dispatch;

import io.arcnode.dercontrol.mirror.ActualActivePowerSubscriber;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Re-establishes this service's MQTT subscriptions after a broker restart.
 *
 * <p>A subscription is broker-side session state, not client-side. The {@code MqttSubscription} and
 * listener held here are only a routing table for inbound messages — they do not make the broker
 * send anything. Paho's {@code automaticReconnect} restores the socket but never re-sends
 * SUBSCRIBE, and {@code cleanStart} defaults to true, so the reconnect asks for a fresh session
 * with no subscriptions in it. Without this, the service publishes normally and receives nothing,
 * forever, with no error and a passing health check.
 *
 * <p>Confirmed against a real HiveMQ container restart: same client, same listener, publish after
 * the restart succeeds and the message is never delivered back.
 *
 * <p>This is also the only thing that subscribes at startup: the subscribers expose {@code
 * subscribe()} but do not listen for {@code ApplicationReadyEvent} themselves. One owner means one
 * thread at a time can be inside {@code MqttClient.subscribe}.
 *
 * <p>The subscribers are listed explicitly rather than collected through an interface, so what gets
 * established is readable in one place. A new subscriber has to be added here, and nowhere else.
 */
@Component
public class MqttReconnectHandler implements MqttCallback {

  private final MqttClient mqtt;

  private static final Logger LOG = LoggerFactory.getLogger(MqttReconnectHandler.class);

  private final DeliveryShortfallMonitor shortfallMonitor;
  private final DispatchCommandSubscriber commandSubscriber;
  private final ActualActivePowerSubscriber actualPowerSubscriber;
  private final OperatorPolicySubscriber policySubscriber;

  public MqttReconnectHandler(
      MqttClient mqtt,
      DeliveryShortfallMonitor shortfallMonitor,
      DispatchCommandSubscriber commandSubscriber,
      ActualActivePowerSubscriber actualPowerSubscriber,
      OperatorPolicySubscriber policySubscriber) {
    this.mqtt = mqtt;
    this.shortfallMonitor = shortfallMonitor;
    this.commandSubscriber = commandSubscriber;
    this.actualPowerSubscriber = actualPowerSubscriber;
    this.policySubscriber = policySubscriber;
  }

  /**
   * Installs this handler. Takes no argument: an {@code @EventListener} method's only parameter is
   * the event itself, so injecting the client here would silently never run. Per-topic listeners
   * registered by {@code subscribe} keep receiving their own messages, so taking the callback slot
   * does not divert message delivery.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void install() {
    mqtt.setCallback(this);
    subscribeAll();
  }

  @Override
  public void connectComplete(boolean reconnect, String serverUri) {
    if (!reconnect) {
      return;
    }
    LOG.warn("🔌 Reconnected to {} — re-establishing subscriptions", serverUri);
    subscribeAll();
  }

  /**
   * Subscribe every subscriber, one caller at a time.
   *
   * <p>Reason for the lock: this runs on two threads. Startup calls it on the main thread, and
   * {@code connectComplete} calls it on Paho's callback thread, so a broker flap during startup had
   * both inside {@code MqttClient.subscribe} at once — which throws {@code
   * ConcurrentModificationException} from inside Paho and fails the context *after* it has reported
   * itself started. The container then sat in Docker's running state, serving nothing, with nothing
   * to restart it.
   */
  private synchronized void subscribeAll() {
    resubscribe("delivery shortfall", shortfallMonitor::subscribe);
    resubscribe("dispatch commands", commandSubscriber::subscribe);
    resubscribe("actual active power", actualPowerSubscriber::subscribe);
    resubscribe("operator policy", policySubscriber::subscribe);
  }

  /**
   * One failure must not leave the remaining subscriptions unrestored.
   *
   * <p>Catches {@code Exception} rather than {@code MqttException}: subscribing also restates
   * retained state, and a failed publish surfaces as an unchecked {@code IllegalStateException}.
   * Anything escaping here runs on Paho's callback thread, where it kills the thread that drives
   * reconnect — so one subscriber's bad luck would cost every subscription and every future
   * recovery, not just its own.
   */
  private void resubscribe(String what, Resubscribe action) {
    try {
      action.run();
      LOG.info("🔌 Resubscribed: {}", what);
    } catch (Exception e) {
      LOG.error("🔌 Could not resubscribe {} — this service is deaf on it until restart", what, e);
    }
  }

  @FunctionalInterface
  private interface Resubscribe {
    void run() throws MqttException;
  }

  @Override
  public void disconnected(MqttDisconnectResponse response) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("🔌 Broker connection lost: {}", response.getReasonString());
    }
  }

  @Override
  public void mqttErrorOccurred(MqttException exception) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("🔌 MQTT error: {}", exception.getMessage());
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    // Per-topic listeners registered at subscribe time handle delivery; nothing routes here.
  }

  @Override
  public void deliveryComplete(IMqttToken token) {
    // Publishes are fire-and-forget at QoS 0.
  }

  @Override
  public void authPacketArrived(int reasonCode, MqttProperties properties) {
    // Enhanced authentication isn't used.
  }
}
