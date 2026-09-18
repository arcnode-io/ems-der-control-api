package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Unit — DerEvent → canonical MQTT sample mapping. Mocked broker, fixed clock, AAA. */
@ExtendWith(MockitoExtension.class)
class DispatchPublisherTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final String BASE = "sites/site_001/devices/der_dispatch/measurements/";

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
  @Captor private ArgumentCaptor<byte[]> payload;

  private DispatchPublisher publisher() {
    return new DispatchPublisher(mqtt, mapper, config, Clock.fixed(FIXED, ZoneOffset.UTC));
  }

  private static DerEvent event(Double targetW, Boolean energize, DerControlStatus status) {
    return new DerEvent("mrid-1", status, FIXED, 3600L, targetW, energize, "{}", "lfdi-test");
  }

  @Test
  void publishesTargetActivePowerAsSignedFloatSample() throws Exception {
    // Arrange
    DerEvent e = event(-1_500_000.0, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt)
        .publish(eq(BASE + "target_active_power/watts"), payload.capture(), eq(0), eq(true));
    JsonNode body = mapper.readTree(payload.getValue());
    assertThat(body.get("ts").asText()).isEqualTo("2026-09-08T14:00:00Z");
    assertThat(body.get("value").asDouble()).isEqualTo(-1_500_000.0);
  }

  @Test
  void alwaysPublishesEventActiveBooleanSample() throws Exception {
    // Arrange
    DerEvent e = event(null, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt).publish(eq(BASE + "event_active/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asBoolean()).isTrue();
  }

  @Test
  void eventActiveIsFalseWhenNotActive() throws Exception {
    // Arrange
    DerEvent e = event(null, null, DerControlStatus.SCHEDULED);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt).publish(eq(BASE + "event_active/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asBoolean()).isFalse();
  }

  @Test
  void publishesEnergizeEnabledWhenPresent() throws Exception {
    // Arrange
    DerEvent e = event(null, Boolean.TRUE, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt).publish(eq(BASE + "energize_enabled/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asBoolean()).isTrue();
  }

  @Test
  void skipsTargetChannelWhenNoTargetPower() throws Exception {
    // Arrange
    DerEvent e = event(null, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt, never()).publish(startsWith(BASE + "target_active_power"), any(), eq(0), eq(true));
  }

  @Test
  void skipsEnergizeChannelWhenAbsent() throws Exception {
    // Arrange
    DerEvent e = event(1_000.0, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt, never()).publish(startsWith(BASE + "energize_enabled"), any(), eq(0), eq(true));
  }
}
