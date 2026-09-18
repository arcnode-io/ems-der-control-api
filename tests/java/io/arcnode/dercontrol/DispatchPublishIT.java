package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The end-to-end proof: {@code POST /der-events} over real HTTP → real Postgres → a real sample on
 * the real broker. Subscribes independently rather than trusting a mock — this is the one test that
 * would catch a topic-string or QoS/retain typo the unit tests can't see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DispatchPublishIT extends AbstractBrokerIT {

  private static final String VALID_BODY =
      """
      {
        "mrid": "%s",
        "eventStatus": "ACTIVE",
        "interval": { "start": "2026-09-08T14:00:00Z", "durationSeconds": 3600 },
        "derControlBase": { "opModTargetW": -1500000.0, "opModEnergize": true }
      }
      """;

  private record ReceivedSample(String topic, String payload) {}

  @LocalServerPort int port;
  @Autowired Config config;
  RestTestClient rest;
  MqttClient subscriber;
  private final BlockingQueue<ReceivedSample> received = new LinkedBlockingQueue<>();

  @BeforeEach
  void setup() throws Exception {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

    subscriber =
        new MqttClient(
            "tcp://" + HIVEMQ.getHost() + ":" + HIVEMQ.getMqttPort(),
            "test-subscriber-" + UUID.randomUUID(),
            new MemoryPersistence());
    subscriber.connect();
    String topicFilter = "sites/%s/devices/der_dispatch/measurements/#".formatted(config.siteId());
    // Reason: MqttClient.subscribe(String, int, IMqttMessageListener) recurses into itself and
    // stack-overflows — a confirmed Paho 1.2.5 bug (eclipse-paho/paho.mqtt.java#917/#863/#816).
    // The MqttSubscription[]/IMqttMessageListener[] overload it's supposed to delegate to is fine.
    subscriber.subscribe(
        new MqttSubscription[] {new MqttSubscription(topicFilter, 0)},
        new IMqttMessageListener[] {
          (topic, message) ->
              received.add(
                  new ReceivedSample(
                      topic, new String(message.getPayload(), StandardCharsets.UTF_8)))
        });
  }

  @AfterEach
  void teardown() throws Exception {
    subscriber.disconnect();
    subscriber.close();
  }

  @Test
  void postPublishesSetpointToBroker() throws Exception {
    // Arrange
    String mrid = "mrid-publish-it";

    // Act
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated();

    // Assert: the exact channel + payload the utility's setpoint should land on
    ReceivedSample sample =
        awaitTopic(
            "sites/%s/devices/der_dispatch/measurements/target_active_power/watts"
                .formatted(config.siteId()));
    assertThat(sample.payload()).contains("\"value\":-1500000.0");
  }

  /**
   * Drains the queue until the wanted topic shows up or the budget runs out — bounded, no sleep.
   */
  private ReceivedSample awaitTopic(String topic) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      ReceivedSample sample = received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (sample == null) {
        break;
      }
      if (sample.topic().equals(topic)) {
        return sample;
      }
    }
    throw new AssertionError("no message on " + topic + " within 10s");
  }
}
