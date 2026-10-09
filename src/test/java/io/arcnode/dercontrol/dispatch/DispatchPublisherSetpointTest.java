package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerControlStatus;
import io.arcnode.dercontrol.derevent.DerEvent;
import io.arcnode.dercontrol.derevent.DerProgram;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — the {@code target_setpoint_present} channel, which says whether a zero on {@code
 * target_active_power} is a command or an absence. Kept apart from {@link DispatchPublisherTest}
 * because that file is already past the 200-line budget. Mocked broker, fixed clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class DispatchPublisherSetpointTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final String TOPIC =
      "sites/local_site/devices/der_dispatch/measurements/target_setpoint_present/none";

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

  @BeforeEach
  void defaultToAutoMode() {
    org.mockito.Mockito.lenient()
        .when(dispatchSettings.currentMode())
        .thenReturn(DispatchMode.AUTO);
  }

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        new MeasurementPublisher(mqtt, mapper, config),
        Clock.fixed(FIXED, ZoneOffset.UTC),
        dispatchSettings);
  }

  private static DerEvent event(Double targetW, DerControlStatus status) {
    return new DerEvent(
        "mrid-1",
        status,
        FIXED,
        3600L,
        targetW,
        true,
        null,
        null,
        "{}",
        "lfdi-test",
        DerProgram.DLR_LINE_CONSTRAINT);
  }

  private boolean publishedPresence(DerEvent event) throws Exception {
    publisher().publish(event);
    verify(mqtt).publish(eq(TOPIC), payload.capture(), eq(0), eq(true));
    return mapper.readTree(payload.getValue()).get("value").asBoolean();
  }

  @Test
  void isTrueWhenAnActiveEventCarriesASetpoint() throws Exception {
    // Arrange
    DerEvent withSetpoint = event(-1_500_000.0, DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(publishedPresence(withSetpoint)).isTrue();
  }

  @Test
  void isFalseWhenAnActiveEventCommandsNoSetpoint() throws Exception {
    // Arrange: a line-constraint event carries an envelope and no opModTargetW, so the zero
    // published on target_active_power means "nothing commanded", not "command zero watts".
    DerEvent envelopeDriven = event(null, DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(publishedPresence(envelopeDriven)).isFalse();
  }

  @Test
  void isFalseWhileAnOperatorHasNotDecidedYet() throws Exception {
    // Arrange: manual mode with no decision is PENDING. A setpoint is written straight through to
    // plant, so it must not leave here until someone has actually approved the dispatch.
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);
    DerEvent awaitingApproval = event(-1_500_000.0, DerControlStatus.ACTIVE);

    // Act / Assert
    assertThat(publishedPresence(awaitingApproval)).isFalse();
  }

  @Test
  void publishesZeroOnTheTargetChannelWhileAnOperatorHasNotDecidedYet() throws Exception {
    // Arrange
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);

    // Act
    publisher().publish(event(-1_500_000.0, DerControlStatus.ACTIVE));

    // Assert: zero, not the pending setpoint — the channel is retained and read straight through
    verify(mqtt)
        .publish(
            eq("sites/local_site/devices/der_dispatch/measurements/target_active_power/watts"),
            payload.capture(),
            eq(0),
            eq(true));
    assertThat(mapper.readTree(payload.getValue()).get("value").asDouble()).isEqualTo(0.0);
  }

  @Test
  void isFalseWhenTheEventIsOverEvenThoughItCarriedASetpoint() throws Exception {
    // Arrange: the setpoint is still on the row, but a cancelled event commands nothing.
    DerEvent cancelled = event(-1_500_000.0, DerControlStatus.CANCELLED);

    // Act / Assert
    assertThat(publishedPresence(cancelled)).isFalse();
  }
}
