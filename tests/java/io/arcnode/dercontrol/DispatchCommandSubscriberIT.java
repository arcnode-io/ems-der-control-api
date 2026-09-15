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
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * The end-to-end proof for the manual-approval loop: a real {@code approve_dispatch}/{@code
 * reject_dispatch} MQTT command, sent by an independent client acting as the operator/HMI, is
 * actually picked up by {@code DispatchCommandSubscriber} (subscribed for real on app startup) and
 * moves a real, ingested event's real state. Forces {@code dispatchMode=manual} for this test class
 * only — every other {@code *IT} keeps the default {@code auto} from cfg.yml.
 *
 * <p>The decisive assertion is the real {@code dispatch_state} sample on the wire, exact-matched
 * against the expected value — the same command actually traveled over MQTT and the real, running
 * {@code DispatchCommandSubscriber} actually applied it. That's only trustworthy because {@code
 * AbstractBrokerIT} carries {@code @DirtiesContext(AFTER_CLASS)}: without it, Spring's test-context
 * cache can keep an earlier {@code *IT} class's context (and its own subscribed {@code
 * DispatchCommandSubscriber}) alive on this same shared broker while this class runs, and since
 * {@code der_dispatch}'s device_id is a fixed constant (not mRID-scoped), that stale context can
 * independently resolve and publish a *different*, leftover pending event to this exact topic in
 * the same window — see {@code AbstractBrokerIT}'s javadoc for the full story. {@code
 * awaitApproved} adds a second, mRID-scoped confirmation straight from the row itself —
 * belt-and-suspenders, not a workaround for the topic being shared.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DispatchCommandSubscriberIT extends AbstractBrokerIT {

  private static final String VALID_BODY =
      """
      {
        "mrid": "%s",
        "eventStatus": "SCHEDULED",
        "interval": { "start": "2026-09-08T14:00:00Z", "durationSeconds": 3600 },
        "derControlBase": { "opModTargetW": -1500000.0, "opModEnergize": true }
      }
      """;
  private static final String STATE_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/dispatch_state/none";
  private static final String APPROVE_TOPIC =
      "sites/site_001/devices/der_dispatch/commands/enable/event_active/none";

  @DynamicPropertySource
  static void dispatchMode(DynamicPropertyRegistry registry) {
    registry.add("app.dispatchMode", () -> "manual");
  }

  private record ReceivedSample(String topic, String payload) {}

  @LocalServerPort int port;
  @Autowired JsonMapper mapper;
  RestTestClient rest;
  MqttClient operator;
  private final BlockingQueue<ReceivedSample> received = new LinkedBlockingQueue<>();

  @BeforeEach
  void setup() throws Exception {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();

    operator =
        new MqttClient(
            "tcp://" + HIVEMQ.getHost() + ":" + HIVEMQ.getMqttPort(),
            "test-operator-" + UUID.randomUUID(),
            new MemoryPersistence());
    operator.connect();
    // Reason: see DispatchPublishIT — the single-topic subscribe overload recurses/stack-overflows
    // in this Paho version; the array overload is fine.
    operator.subscribe(
        new MqttSubscription[] {new MqttSubscription(STATE_TOPIC, 0)},
        new IMqttMessageListener[] {
          (topic, message) ->
              received.add(
                  new ReceivedSample(
                      topic, new String(message.getPayload(), StandardCharsets.UTF_8)))
        });
  }

  @AfterEach
  void teardown() throws Exception {
    operator.disconnect();
    operator.close();
  }

  @Test
  void approveCommandFromAnIndependentClientMovesTheRealEventToArmed() throws Exception {
    // Arrange: drain any retained dispatch_state left on the broker by another *IT class sharing
    // this same singleton container — dispatch_state is retained and der_dispatch's device_id is
    // a fixed constant (not mRID-scoped), so DispatchPublishIT publishes to this exact topic too.
    // See DispatchPublishIT.setup() for the same pattern — loop, not a single poll, in case more
    // than one retained value is already queued.
    ReceivedSample stale;
    do {
      stale = received.poll(300, TimeUnit.MILLISECONDS);
    } while (stale != null);

    // Ingest a real event in manual mode — publishes PENDING immediately
    String mrid = "mrid-command-it";
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated();
    assertThat(awaitState()).isEqualTo("PENDING");

    // Act: an independent client (standing in for the HMI) publishes the real approve command —
    // not calling any service method directly, only the wire
    operator.publish(APPROVE_TOPIC, new MqttMessage("{}".getBytes(StandardCharsets.UTF_8)));

    // Assert: the real command traveled over MQTT and der-control-api's real, running subscriber
    // applied it — decisive because @DirtiesContext on AbstractBrokerIT guarantees this is the
    // only live context subscribed to this broker right now (see class javadoc).
    assertThat(awaitState()).isEqualTo("ARMED");

    // Assert: the underlying row itself reflects the decision too, scoped by mrid — an
    // additional, independent confirmation straight from the data, not just the wire.
    awaitApproved(mrid);
  }

  /** Polls GET /der-events/{mrid} until `approved` is true — bounded, no sleep. */
  private void awaitApproved(String mrid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      String body =
          rest.get()
              .uri("/der-events/{mrid}", mrid)
              .exchange()
              .expectStatus()
              .isOk()
              .returnResult(String.class)
              .getResponseBody();
      if (body != null && body.contains("\"approved\":true")) {
        return;
      }
      TimeUnit.MILLISECONDS.sleep(200);
    }
    throw new AssertionError(mrid + " never reached approved=true within 10s");
  }

  /** Polls until a sample lands on the fixed dispatch_state topic, and returns its value. */
  private String awaitState() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      ReceivedSample sample = received.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
      if (sample == null) {
        break;
      }
      if (STATE_TOPIC.equals(sample.topic())) {
        return mapper.readTree(sample.payload()).get("value").asText();
      }
    }
    throw new AssertionError("no dispatch_state sample within 10s");
  }
}
