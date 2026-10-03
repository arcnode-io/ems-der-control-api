package io.arcnode.dercontrol.dispatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — an event's limits reach {@code operating_envelope} only while that event is in force. A
 * limit is what physically moves the battery, so publishing one from an event still awaiting an
 * operator's decision would let the plant act on a dispatch nobody approved. Mocked broker, fixed
 * clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class DispatchPublisherEnvelopeTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final String IMPORT_LIMIT =
      "sites/local_site/devices/operating_envelope/measurements/import_limit/watts";

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

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        mqtt, mapper, config, Clock.fixed(FIXED, ZoneOffset.UTC), dispatchSettings);
  }

  /** A bounded constraint: an import limit with an interval already open, and no setpoint. */
  private static DerEvent constraint(DerControlStatus status, Boolean approved) {
    DerEvent event =
        new DerEvent("mrid-1", status, FIXED, 3600L, null, true, 0.0, null, "{}", "lfdi-test");
    event.setApproved(approved);
    return event;
  }

  @Test
  void withholdsTheLimitWhileAnOperatorHasNotDecided() throws Exception {
    // Arrange: manual mode, nobody has approved yet, so this event is PENDING
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);

    // Act
    publisher().publish(constraint(DerControlStatus.ACTIVE, null));

    // Assert
    verify(mqtt, never()).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
  }

  @Test
  void publishesTheLimitOnceTheEventIsInForce() throws Exception {
    // Arrange
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.AUTO);

    // Act
    publisher().publish(constraint(DerControlStatus.ACTIVE, null));

    // Assert
    verify(mqtt).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
  }

  @Test
  void withholdsTheLimitOnceTheUtilityHasCancelled() throws Exception {
    // Arrange: a cancelled event commands nothing, so its limit must not linger
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.AUTO);

    // Act
    publisher().publish(constraint(DerControlStatus.CANCELLED, null));

    // Assert
    verify(mqtt, never()).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
  }

  @Test
  void withholdsTheLimitWhenTheOperatorRejected() throws Exception {
    // Arrange
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);

    // Act
    publisher().publish(constraint(DerControlStatus.ACTIVE, Boolean.FALSE));

    // Assert
    verify(mqtt, never()).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
  }
}
