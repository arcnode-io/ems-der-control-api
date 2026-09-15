package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.topology.TopologyClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/** Unit — mocked broker + topology client, real JsonMapper, fixed clock. AAA. */
@ExtendWith(MockitoExtension.class)
class AssetCommandPublisherTest {

  private static final Instant FIXED = Instant.parse("2026-09-08T14:00:00Z");
  private static final Config CONFIG =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "localhost",
          "tcp://localhost:1883",
          "arcnode_der_control_api",
          "site_001",
          Config.DispatchMode.AUTO,
          "http://localhost:3000");

  @Mock private MqttClient mqtt;
  @Mock private TopologyClient topologyClient;
  @Captor private ArgumentCaptor<byte[]> payload;
  private final JsonMapper mapper = JsonMapper.builder().build();

  private AssetCommandPublisher publisher() {
    return new AssetCommandPublisher(
        mqtt, mapper, CONFIG, Clock.fixed(FIXED, ZoneOffset.UTC), topologyClient);
  }

  @Test
  void commandsTheResolvedBessModule() throws Exception {
    // Arrange
    given(topologyClient.findBessModuleDeviceId()).willReturn(Optional.of("bess_module_1"));

    // Act
    publisher().publishSetpoint(-1_500_000.0);

    // Assert
    verify(mqtt)
        .publish(
            eq("sites/site_001/devices/bess_module_1/commands/set/active_power/watts"),
            payload.capture(),
            eq(1),
            eq(false));
    assertThat(mapper.readTree(payload.getValue()).get("value").asDouble()).isEqualTo(-1_500_000.0);
  }

  @Test
  void publishesNothingWhenTopologyHasNoBessModule() throws Exception {
    // Arrange: nothing provisioned yet in device-api's topology
    given(topologyClient.findBessModuleDeviceId()).willReturn(Optional.empty());

    // Act
    publisher().publishSetpoint(-1_500_000.0);

    // Assert: no exception, no attempt to command a device that doesn't exist
    verify(mqtt, never()).publish(anyString(), any(), anyInt(), anyBoolean());
  }
}
