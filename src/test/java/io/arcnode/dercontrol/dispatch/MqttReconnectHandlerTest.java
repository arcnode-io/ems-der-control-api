package io.arcnode.dercontrol.dispatch;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.mirror.ActualActivePowerSubscriber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — an MQTT subscription is broker-side session state, not client-side. Paho reconnects the
 * socket but never re-sends SUBSCRIBE, and cleanStart defaults to true, so after a broker restart
 * this service publishes normally and receives nothing, with no error anywhere. Verified against a
 * real HiveMQ restart before writing this. AAA.
 */
@ExtendWith(MockitoExtension.class)
class MqttReconnectHandlerTest {

  @Mock private org.eclipse.paho.mqttv5.client.MqttClient mqtt;
  @Mock private DeliveryShortfallMonitor shortfallMonitor;
  @Mock private DispatchCommandSubscriber commandSubscriber;
  @Mock private ActualActivePowerSubscriber actualPowerSubscriber;

  private MqttReconnectHandler handler() {
    return new MqttReconnectHandler(
        mqtt, shortfallMonitor, commandSubscriber, actualPowerSubscriber);
  }

  @Test
  void resubscribesEverySubscriberAfterAReconnect() throws Exception {
    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert: every subscription this service depends on has to be re-established
    verify(shortfallMonitor).subscribe();
    verify(commandSubscriber).subscribe();
    verify(actualPowerSubscriber).subscribe();
  }

  @Test
  void doesNothingOnTheFirstConnect() throws Exception {
    // Arrange: ApplicationReadyEvent already subscribes at boot, so doing it again here would
    // double-register listeners for no reason
    handler().connectComplete(false, "tcp://broker:1883");

    // Assert
    verify(shortfallMonitor, never()).subscribe();
    verify(commandSubscriber, never()).subscribe();
    verify(actualPowerSubscriber, never()).subscribe();
  }

  @Test
  void aFailedResubscribeDoesNotStopTheRest() throws Exception {
    // Arrange: one subscriber failing must not leave the others deaf too
    org.mockito.BDDMockito.willThrow(new org.eclipse.paho.mqttv5.common.MqttException(0))
        .given(shortfallMonitor)
        .subscribe();

    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert
    verify(commandSubscriber).subscribe();
    verify(actualPowerSubscriber).subscribe();
  }

  @Test
  void installItselfAsTheClientsCallback() {
    // Arrange: an @EventListener method's only parameter is the event, so taking the client as an
    // argument here would silently never run and nothing would ever be resubscribed
    MqttReconnectHandler handler = handler();

    // Act
    handler.install();

    // Assert
    verify(mqtt).setCallback(handler);
  }
}
