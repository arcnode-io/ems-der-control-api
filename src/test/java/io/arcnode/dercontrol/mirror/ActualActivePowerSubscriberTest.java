package io.arcnode.dercontrol.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.arcnode.dercontrol.Config;
import java.time.Duration;
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
 * Unit — holds der_dispatch's latest {@code actual_active_power}, independently of {@link
 * io.arcnode.dercontrol.dispatch.DeliveryShortfallMonitor}'s own subscription to the same topic —
 * mirror reporting is its own concern, not entangled with shortfall detection. Mocked broker, real
 * JsonMapper, AAA.
 */
@ExtendWith(MockitoExtension.class)
class ActualActivePowerSubscriberTest {

  private static final String TOPIC =
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
          "site_001",
          "http://localhost:8081");
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Mock private MqttClient mqtt;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private ActualActivePowerSubscriber subscriber() {
    return new ActualActivePowerSubscriber(mqtt, config, mapper);
  }

  private static MqttMessage sample(double value) {
    return new MqttMessage(
        ("{\"ts\":\"2026-09-21T00:00:00Z\",\"value\":" + value + "}").getBytes());
  }

  @Test
  void hasNoReadingUntilFirstMessageArrives() {
    // Act / Assert
    assertThat(subscriber().currentActiveWatts()).isNull();
  }

  @Test
  void subscribesToTheCanonicalActualActivePowerTopicOnStartup() throws Exception {
    // Act
    subscriber().subscribe();

    // Assert
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(TOPIC);
  }

  @Test
  void holdsTheLatestReadingAfterAMessageArrives() throws Exception {
    // Arrange
    ActualActivePowerSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act
    listener.messageArrived(TOPIC, sample(612_500.0));

    // Assert: guard() processes off the Paho thread on a background executor — poll rather than
    // assert synchronously, same reasoning as DlrRatingSubscriber's own async handoff.
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (subscriber.currentActiveWatts() == null && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(subscriber.currentActiveWatts()).isEqualTo(612_500.0);
  }

  @Test
  void aMalformedPayloadDoesNotPropagateFromTheListener() throws Exception {
    // Arrange
    ActualActivePowerSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act / Assert
    assertThatCode(() -> listener.messageArrived(TOPIC, new MqttMessage("not json".getBytes())))
        .doesNotThrowAnyException();
  }
}
