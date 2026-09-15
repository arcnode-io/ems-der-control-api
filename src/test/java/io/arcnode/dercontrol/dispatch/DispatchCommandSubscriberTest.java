package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.derevent.DerEventService;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — subscribes on boot to der_dispatch's approve/reject commands and routes them to {@link
 * DerEventService}. Mocked broker (real delivery is AbstractBrokerIT's job), AAA.
 */
@ExtendWith(MockitoExtension.class)
class DispatchCommandSubscriberTest {

  private static final String APPROVE_TOPIC =
      "sites/site_001/devices/der_dispatch/commands/enable/event_active/none";
  private static final String REJECT_TOPIC =
      "sites/site_001/devices/der_dispatch/commands/disable/event_active/none";

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
          Config.DispatchMode.MANUAL,
          "http://localhost:3000");

  @Mock private MqttClient mqtt;
  @Mock private DerEventService service;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  @Test
  void subscribesToApproveAndRejectTopicsOnStartup() throws Exception {
    // Act
    new DispatchCommandSubscriber(mqtt, config, service).subscribe();

    // Assert
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(APPROVE_TOPIC, REJECT_TOPIC);
  }

  @Test
  void approveTopicMessageCallsApproveCurrentPending() throws Exception {
    // Arrange
    new DispatchCommandSubscriber(mqtt, config, service).subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());

    // Act: simulate the broker delivering a message on the approve topic
    listeners.getValue()[0].messageArrived(APPROVE_TOPIC, new MqttMessage("{}".getBytes()));

    // Assert: handled off-thread (see DispatchCommandSubscriber.guard) — never call the client
    // back synchronously from its own delivery thread, so this has to poll rather than assert
    // immediately
    verify(service, timeout(1000)).approveCurrentPending();
    verify(service, never()).rejectCurrentPending();
  }

  @Test
  void rejectTopicMessageCallsRejectCurrentPending() throws Exception {
    // Arrange
    new DispatchCommandSubscriber(mqtt, config, service).subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());

    // Act
    listeners.getValue()[1].messageArrived(REJECT_TOPIC, new MqttMessage("{}".getBytes()));

    // Assert
    verify(service, timeout(1000)).rejectCurrentPending();
    verify(service, never()).approveCurrentPending();
  }

  @Test
  void aThrowingServiceCallDoesNotPropagateFromTheListener() throws Exception {
    // Arrange: a real broker callback thread must not die on a bad payload or a DB hiccup
    Mockito.doThrow(new RuntimeException("boom")).when(service).approveCurrentPending();
    new DispatchCommandSubscriber(mqtt, config, service).subscribe();
    verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());

    // Act
    listeners.getValue()[0].messageArrived(APPROVE_TOPIC, new MqttMessage("{}".getBytes()));

    // Assert: no exception escapes messageArrived, and the handler really did run (and fail)
    verify(service, timeout(1000)).approveCurrentPending();
  }
}
