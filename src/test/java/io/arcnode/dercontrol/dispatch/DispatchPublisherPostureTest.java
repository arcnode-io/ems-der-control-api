package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — the posture der_dispatch states when no event is in force. Its own class because the idle
 * posture consults no dispatch mode, and DispatchPublisherTest stubs one for every test. AAA.
 */
@ExtendWith(MockitoExtension.class)
class DispatchPublisherPostureTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final String BASE = "sites/local_site/devices/der_dispatch/measurements/";

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
  @Mock private DispatchSettingsService dispatchSettings;
  @Captor private ArgumentCaptor<byte[]> payload;

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        new MeasurementPublisher(mqtt, mapper, config),
        Clock.fixed(FIXED, ZoneOffset.UTC),
        dispatchSettings);
  }

  @Test
  void theIdlePostureNamesNoProgram() throws Exception {
    // Arrange / Act: nothing in force
    publisher().publishIdlePosture();

    // Assert: retained alongside event_active false, so a consumer never sees a stale program
    // name from the last event outlive the event itself
    verify(mqtt).publish(eq(BASE + "der_event_program/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asText()).isEqualTo("NONE");
  }

  @Test
  void theIdlePostureSaysNoEventAndNoSetpoint() throws Exception {
    // Arrange / Act
    publisher().publishIdlePosture();

    // Assert: the four retained channels a consumer gates on, stated together
    verify(mqtt).publish(eq(BASE + "event_active/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asBoolean()).isFalse();
    verify(mqtt)
        .publish(eq(BASE + "target_setpoint_present/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asBoolean()).isFalse();
    verify(mqtt).publish(eq(BASE + "der_event_state/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asText()).isEqualTo("IDLE");
  }
}
