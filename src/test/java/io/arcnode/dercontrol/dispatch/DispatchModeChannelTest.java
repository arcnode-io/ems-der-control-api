package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Unit — the {@code dispatch_mode} channel pair. The mode rides the broker rather than an HTTP PUT
 * because der-control-api terminates no auth of its own, and the broker already authenticates the
 * operator and already carries approve/reject. Mocked broker, fixed clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class DispatchModeChannelTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final String STATE =
      "sites/local_site/devices/der_dispatch/measurements/dispatch_mode/none";
  private static final String COMMAND =
      "sites/local_site/devices/der_dispatch/commands/set/dispatch_mode/none";

  private final Config config =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "localhost",
          "tcp://localhost:1883",
          "arcnode_der_control_api",
          "local_site",
          "http://localhost:8081",
          "http://localhost:8080");
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Mock private MqttClient mqtt;
  @Mock private DispatchSettingsService settings;
  @Captor private ArgumentCaptor<byte[]> payload;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        mqtt, mapper, config, Clock.fixed(FIXED, ZoneOffset.UTC), settings);
  }

  private DispatchModeSubscriber subscriber() {
    return new DispatchModeSubscriber(mqtt, config, settings, mapper, publisher());
  }

  @Test
  void publishesTheModeAsItsLabelSoAnOperatorScreenCanRenderIt() throws Exception {
    // Act
    publisher().publishDispatchMode(DispatchMode.MANUAL);

    // Assert: retained, so a screen opened later still knows the posture
    verify(mqtt).publish(eq(STATE), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asString()).isEqualTo("MANUAL");
  }

  @Test
  void subscribesToTheSetCommandAndRepublishesCurrentModeOnConnect() throws Exception {
    // Arrange
    org.mockito.BDDMockito.given(settings.currentMode()).willReturn(DispatchMode.AUTO);

    // Act
    subscriber().subscribe();

    // Assert: the retained state is refreshed on every (re)connect, not only on change
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(COMMAND);
    verify(mqtt).publish(eq(STATE), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asString()).isEqualTo("AUTO");
  }

  @Test
  void anOperatorCommandSetsTheModeAndEchoesItBack() throws Exception {
    // Arrange
    org.mockito.BDDMockito.given(settings.currentMode()).willReturn(DispatchMode.AUTO);
    subscriber().subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());

    // Act: the system-wide envelope, value carrying the label
    listeners.getValue()[0].messageArrived(
        COMMAND,
        new MqttMessage("{\"ts\":\"2026-10-03T12:00:00Z\",\"value\":\"MANUAL\"}".getBytes()));

    // Assert
    verify(settings, timeout(5000)).setMode(DispatchMode.MANUAL);
  }
}
