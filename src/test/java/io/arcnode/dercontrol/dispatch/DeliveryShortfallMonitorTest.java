package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — subscribes to der_dispatch's target_active_power/event_active/actual_active_power and
 * detects sustained non-delivery (Phase III). Mocked broker + publisher, real JsonMapper, AAA.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryShortfallMonitorTest {

  private static final String TARGET_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/target_active_power/watts";
  private static final String EVENT_ACTIVE_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/event_active/none";
  private static final String ACTUAL_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/actual_active_power/watts";

  private final Config config =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "localhost",
          "tcp://localhost:1883",
          "arcnode_der_control_api",
          "site_001");
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Mock private MqttClient mqtt;
  @Mock private DispatchPublisher publisher;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private DeliveryShortfallMonitor monitor() {
    return new DeliveryShortfallMonitor(mqtt, config, mapper, publisher);
  }

  private static MqttMessage sample(double value) {
    return new MqttMessage(
        ("{\"ts\":\"2026-09-20T00:00:00Z\",\"value\":" + value + "}").getBytes());
  }

  private static MqttMessage sample(boolean value) {
    return new MqttMessage(
        ("{\"ts\":\"2026-09-20T00:00:00Z\",\"value\":" + value + "}").getBytes());
  }

  @Test
  void subscribesToTargetEventActiveAndActualTopicsOnStartup() throws Exception {
    // Act
    monitor().subscribe();

    // Assert
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(TARGET_TOPIC, EVENT_ACTIVE_TOPIC, ACTUAL_TOPIC);
  }

  @Test
  void publishesShortfallAfterThresholdConsecutiveGaps() throws Exception {
    // Arrange: event active, target 1000W, actual repeatedly far below target
    DeliveryShortfallMonitor monitor = monitor();
    monitor.subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener eventActiveListener = listeners.getValue()[1];
    IMqttMessageListener actualListener = listeners.getValue()[2];
    targetListener.messageArrived(TARGET_TOPIC, sample(1000.0));
    eventActiveListener.messageArrived(EVENT_ACTIVE_TOPIC, sample(true));

    // Act: three consecutive large gaps (below the shortfall threshold tick count)
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));

    // Assert
    verify(publisher, timeout(1000)).publishShortfall(true);
  }

  @Test
  void doesNotPublishShortfallBeforeThresholdReached() throws Exception {
    // Arrange
    DeliveryShortfallMonitor monitor = monitor();
    monitor.subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener eventActiveListener = listeners.getValue()[1];
    IMqttMessageListener actualListener = listeners.getValue()[2];
    targetListener.messageArrived(TARGET_TOPIC, sample(1000.0));
    eventActiveListener.messageArrived(EVENT_ACTIVE_TOPIC, sample(true));

    // Act: only two gaps — one short of the threshold
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));

    // Assert: give the async executor a moment, then confirm it never fired
    verify(publisher, timeout(500).times(0)).publishShortfall(true);
  }

  @Test
  void resetsShortfallWhenDeliveryRecovers() throws Exception {
    // Arrange: already in shortfall
    DeliveryShortfallMonitor monitor = monitor();
    monitor.subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener eventActiveListener = listeners.getValue()[1];
    IMqttMessageListener actualListener = listeners.getValue()[2];
    targetListener.messageArrived(TARGET_TOPIC, sample(1000.0));
    eventActiveListener.messageArrived(EVENT_ACTIVE_TOPIC, sample(true));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(100.0));
    verify(publisher, timeout(1000)).publishShortfall(true);

    // Act: delivery recovers to within tolerance
    actualListener.messageArrived(ACTUAL_TOPIC, sample(990.0));

    // Assert
    verify(publisher, timeout(1000)).publishShortfall(false);
  }

  @Test
  void noShortfallTrackingWhenEventNotActive() throws Exception {
    // Arrange: event_active is false — a stale retained target shouldn't trigger shortfall
    DeliveryShortfallMonitor monitor = monitor();
    monitor.subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener eventActiveListener = listeners.getValue()[1];
    IMqttMessageListener actualListener = listeners.getValue()[2];
    targetListener.messageArrived(TARGET_TOPIC, sample(1000.0));
    eventActiveListener.messageArrived(EVENT_ACTIVE_TOPIC, sample(false));

    // Act
    actualListener.messageArrived(ACTUAL_TOPIC, sample(0.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(0.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(0.0));

    // Assert
    verify(publisher, timeout(500).times(0)).publishShortfall(true);
  }

  @Test
  void aThrowingHandlerDoesNotPropagateFromTheListener() throws Exception {
    // Arrange: malformed payload must not kill the broker's delivery thread
    monitor().subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener actualListener = listeners.getValue()[2];

    // Act / Assert
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                actualListener.messageArrived(ACTUAL_TOPIC, new MqttMessage("not json".getBytes())))
        .doesNotThrowAnyException();
  }
}
