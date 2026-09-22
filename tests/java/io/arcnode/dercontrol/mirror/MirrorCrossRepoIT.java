package io.arcnode.dercontrol.mirror;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.arcnode.dercontrol.AbstractBrokerIT;
import io.arcnode.dercontrol.TestcontainersConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.PullPolicy;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real cross-repo proof, matching ems-industrial-gateway's own dispatch_test.rs pattern (real
 * broker + a real sibling-service container, never a stub): pulls mock-derms-dispatch-api's actual
 * published image ({@code public.ecr.aws/y1d2j6a8/mock-derms-dispatch-api}, pushed by its own CI on
 * every push to main — confirmed public/anonymous-pullable) and runs it for real, on its own
 * network with its own broker (it never talks MQTT to der-control-api — the two services only ever
 * talk over this HTTP path). Deliberately not built from a local sibling checkout via {@code
 * ImageFromDockerfile}: that only works when a developer happens to have both repos checked out as
 * siblings, and this project's own CI runner has no such guarantee (a shell executor, not a per-job
 * isolated checkout) — pulling the published image works identically in both places, and tests
 * against what's actually deployed rather than a local working copy. WireMock-based tests
 * (MirrorUsagePointClientIT) prove der-control-api sends the right request; this proves a genuinely
 * separate, independently-generated JAXB implementation of the same schema can actually parse it —
 * exactly the risk a stub cannot catch.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MirrorCrossRepoIT extends AbstractBrokerIT {

  private static final String MOCK_DERMS_DISPATCH_API_IMAGE =
      "public.ecr.aws/y1d2j6a8/mock-derms-dispatch-api:latest";

  private static final Network NETWORK = Network.newNetwork();
  private static final GenericContainer<?> MOCK_DERMS_BROKER =
      new GenericContainer<>("hivemq/hivemq-ce").withNetwork(NETWORK).withNetworkAliases("hivemq");
  private static GenericContainer<?> mockDermsDispatchApi;

  @BeforeAll
  static void startMockDermsDispatchApi() {
    if (!org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
      return;
    }
    MOCK_DERMS_BROKER.start();
    mockDermsDispatchApi =
        new GenericContainer<>(MOCK_DERMS_DISPATCH_API_IMAGE)
            // Reason: this image is tagged :latest and rebuilt on every push to that repo's main
            // — without forcing a pull, Docker reuses whatever was last pulled, silently testing
            // against a stale image (a shell-executor CI runner keeps a persistent Docker cache
            // across pipeline runs too, not just local dev).
            .withImagePullPolicy(PullPolicy.alwaysPull())
            .withNetwork(NETWORK)
            .withEnv("ENV", "beta")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
    mockDermsDispatchApi.start();
  }

  @AfterAll
  static void stopMockDermsDispatchApi() {
    if (mockDermsDispatchApi != null) {
      mockDermsDispatchApi.stop();
    }
    MOCK_DERMS_BROKER.stop();
  }

  @DynamicPropertySource
  static void utilityMirrorUrl(DynamicPropertyRegistry registry) {
    registry.add(
        "app.utilityMirrorUrl",
        () ->
            "http://"
                + mockDermsDispatchApi.getHost()
                + ":"
                + mockDermsDispatchApi.getMappedPort(8080));
  }

  @Autowired MirrorUsagePointClient client;

  @Test
  void aRealSeparatelyGeneratedMockDermsDispatchApiCanParseOurRealMirrorUsagePointXml() {
    // Act — no exception means der-control-api's real marshalled XML round-tripped through a
    // real HTTP call into a genuinely separate JAXB implementation of the same schema.
    client.post(MirrorUsagePointFactory.build(500_000L));

    // Assert — the real container actually parsed it, not just accepted arbitrary bytes.
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(mockDermsDispatchApi.getLogs())
                    .contains("received MirrorUsagePoint, actual 500000.0W"));
  }
}
