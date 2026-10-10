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
import io.arcnode.dercontrol.derevent.DerProgram;
import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.derevent.DispatchSettingsService;
import io.arcnode.dercontrol.eventlog.EventLogService;
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
 * Unit — an event's limits reach {@code operating_envelope} whenever the utility has them in force,
 * and only then.
 *
 * <p>Operator policy deliberately does not gate them: an operating envelope is the boundary a site
 * must stay inside at all times and cannot decline, so withholding one pending an approval would be
 * designed non-compliance. What an operator does get to decide is which resource answers the
 * envelope — storage or compute — not whether to obey it. A setpoint is the opposite case and is
 * gated, which {@code DispatchPublisherSetpointTest} covers. Mocked broker, fixed clock, AAA.
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
  @Mock private EventLogService eventLog;

  private DispatchPublisher publisher() {
    return new DispatchPublisher(
        new MeasurementPublisher(mqtt, mapper, config),
        Clock.fixed(FIXED, ZoneOffset.UTC),
        dispatchSettings,
        eventLog);
  }

  /** A bounded constraint: an import limit with an interval already open, and no setpoint. */
  private static DerEvent constraint(DerControlStatus status, Boolean approved) {
    DerEvent event =
        new DerEvent(
            "mrid-1",
            status,
            FIXED,
            3600L,
            null,
            true,
            0.0,
            null,
            "{}",
            "lfdi-test",
            DerProgram.DLR_LINE_CONSTRAINT);
    event.setApproved(approved);
    return event;
  }

  @Test
  void appliesTheLimitEvenWhileAnOperatorHasNotDecided() throws Exception {
    // Arrange: manual mode with no decision yet, so the event is PENDING
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);

    // Act
    publisher().publish(constraint(DerControlStatus.ACTIVE, null));

    // Assert: the boundary binds on arrival. Waiting for a click here would mean the site
    // knowingly exceeded a limit the utility had already given it.
    verify(mqtt).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
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
  void appliesTheLimitEvenWhenTheOperatorRefused() throws Exception {
    // Arrange: a refusal cannot reach a mandatory control. An operator refusing storage is a
    // different decision, carried on its own channel, not an opt-out from the envelope.
    given(dispatchSettings.currentMode()).willReturn(DispatchMode.MANUAL);

    // Act
    publisher().publish(constraint(DerControlStatus.ACTIVE, Boolean.FALSE));

    // Assert
    verify(mqtt).publish(eq(IMPORT_LIMIT), any(), anyInt(), anyBoolean());
  }
}
