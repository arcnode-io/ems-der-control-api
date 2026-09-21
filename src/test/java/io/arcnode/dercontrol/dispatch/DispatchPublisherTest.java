package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.junit.jupiter.api.BeforeEach;
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
  private static final String ENVELOPE_BASE =
      "sites/site_001/devices/operating_envelope/measurements/";

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
  @Mock private DispatchSettingsService dispatchSettings;
  @Captor private ArgumentCaptor<byte[]> payload;

  @BeforeEach
  void defaultToAutoMode() {
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.AUTO);
  }

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        mqtt, mapper, config, Clock.fixed(FIXED, ZoneOffset.UTC), dispatchSettings);
  }

  private static DerEvent event(Double targetW, Boolean energize, DerControlStatus status) {
    return event(targetW, energize, null, null, status);
  }

  private static DerEvent event(
      Double targetW,
      Boolean energize,
      Double importLimitW,
      Double exportLimitW,
      DerControlStatus status) {
    return new DerEvent(
        "mrid-1",
        status,
        FIXED,
        3600L,
        targetW,
        energize,
        importLimitW,
        exportLimitW,
        "{}",
        "lfdi-test");
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

  @Test
  void publishesImportLimitToOperatingEnvelopeWhenPresent() throws Exception {
    // Arrange
    DerEvent e = event(null, null, 500_000.0, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt)
        .publish(eq(ENVELOPE_BASE + "import_limit/watts"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asDouble()).isEqualTo(500_000.0);
  }

  @Test
  void publishesExportLimitToOperatingEnvelopeWhenPresent() throws Exception {
    // Arrange
    DerEvent e = event(null, null, null, 300_000.0, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt)
        .publish(eq(ENVELOPE_BASE + "export_limit/watts"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asDouble()).isEqualTo(300_000.0);
  }

  @Test
  void skipsEnvelopeChannelsWhenAbsent() throws Exception {
    // Arrange
    DerEvent e = event(null, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt, never()).publish(startsWith(ENVELOPE_BASE), any(), eq(0), eq(true));
  }

  @Test
  void alwaysPublishesDerEventStateAsEnumSample() throws Exception {
    // Arrange
    DerEvent e = event(null, null, DerControlStatus.ACTIVE);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt).publish(eq(BASE + "der_event_state/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asText()).isEqualTo("ACTIVE");
  }

  @Test
  void derEventStateReflectsManualModePendingWhenNoDecisionYet() throws Exception {
    // Arrange
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);
    DerEvent e = event(null, null, DerControlStatus.SCHEDULED);

    // Act
    publisher().publish(e);

    // Assert
    verify(mqtt).publish(eq(BASE + "der_event_state/none"), payload.capture(), eq(0), eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asText()).isEqualTo("PENDING");
  }
}
