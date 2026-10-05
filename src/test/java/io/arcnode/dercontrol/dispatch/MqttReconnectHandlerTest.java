package io.arcnode.dercontrol.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import io.arcnode.dercontrol.derevent.DerEventService;
import io.arcnode.dercontrol.mirror.ActualActivePowerSubscriber;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
  @Mock private OperatorPolicySubscriber policySubscriber;
  @Mock private DerEventService derEventService;

  private MqttReconnectHandler handler() {
    return new MqttReconnectHandler(
        mqtt,
        shortfallMonitor,
        commandSubscriber,
        actualPowerSubscriber,
        policySubscriber,
        derEventService);
  }

  @Test
  void installSubscribesEverySubscriberItself() throws Exception {
    // Act
    handler().install();

    // Assert: startup subscription runs through this one owner, not through each
    // subscriber's own ApplicationReadyEvent listener. Two entry points meant the
    // main thread and Paho's callback thread could both be inside
    // MqttClient.subscribe at once, which throws ConcurrentModificationException
    // from inside Paho and aborts the context after it has already started.
    verify(shortfallMonitor).subscribe();
    verify(commandSubscriber).subscribe();
    verify(actualPowerSubscriber).subscribe();
    verify(policySubscriber).subscribe();
  }

  @Test
  void resubscribesEverySubscriberAfterAReconnect() throws Exception {
    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert: every subscription this service depends on has to be re-established
    verify(shortfallMonitor).subscribe();
    verify(commandSubscriber).subscribe();
    verify(actualPowerSubscriber).subscribe();
    verify(policySubscriber).subscribe();
  }

  @Test
  void restatesDerDispatchAfterAReconnect() throws Exception {
    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert: a broker restart drops every retained message while this process stays up, so
    // re-establishing only the subscriptions leaves der_dispatch silent — and a consumer that
    // gates real power on event_active stays dark until the next event happens to arrive.
    //
    // Awaited, because it must not run on the calling thread: connectComplete arrives on Paho's
    // callback thread, and a synchronous publish from there deadlocks against the client.
    verify(derEventService, timeout(2000)).statePosture();
  }

  @Test
  void restatesDerDispatchOffTheCallingThread() throws Exception {
    // Arrange: record which thread the restatement actually runs on
    AtomicReference<String> ranOn = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    willAnswer(
            invocation -> {
              ranOn.set(Thread.currentThread().getName());
              done.countDown();
              return null;
            })
        .given(derEventService)
        .statePosture();

    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert: Paho delivers connectComplete on its own callback thread, and a publish issued from
    // that thread blocks forever waiting on the client it is already inside — no error, the log
    // simply stops mid-restatement. So the restatement has to be handed to another thread.
    assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(ranOn.get()).isNotEqualTo(Thread.currentThread().getName());
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
    verify(policySubscriber).subscribe();
  }

  @Test
  void anUncheckedResubscribeFailureDoesNotStopTheRest() throws Exception {
    // Arrange: OperatorPolicySubscriber.subscribe ends by publishing the current mode and
    // reserve, and DispatchPublisher wraps a failed publish in IllegalStateException. Unchecked,
    // so catching only MqttException lets it escape subscribeAll — the later subscribers never
    // run, and on Paho's callback thread it kills the thread that drives reconnect, so the next
    // keepalive timeout never recovers.
    org.mockito.BDDMockito.willThrow(new IllegalStateException("failed to publish"))
        .given(shortfallMonitor)
        .subscribe();

    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert
    verify(commandSubscriber).subscribe();
    verify(actualPowerSubscriber).subscribe();
    verify(policySubscriber).subscribe();
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
