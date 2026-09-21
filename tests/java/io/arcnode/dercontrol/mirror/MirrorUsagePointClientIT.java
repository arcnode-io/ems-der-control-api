package io.arcnode.dercontrol.mirror;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.arcnode.dercontrol.AbstractBrokerIT;
import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.TestcontainersConfiguration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real XML over the wire against a stubbed utility — mirrors mock-derms-dispatch-api's own
 * DerEventsClientIT pattern (real Spring context via AbstractBrokerIT, since MqttConfig's bean
 * connects eagerly at boot regardless of what the test touches).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class MirrorUsagePointClientIT extends AbstractBrokerIT {

  @RegisterExtension
  static WireMockExtension wiremock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("app.utilityMirrorUrl", wiremock::baseUrl);
  }

  @Autowired Config config;
  @Autowired MirrorUsagePointClient client;

  @BeforeEach
  void setup() {
    // Reason: e2e profile (beta) points at a real endpoint — skip the stubbed assertion there.
    Assumptions.assumeFalse(config.e2e(), "e2e profile hits the real downstream");
  }

  @Test
  void postsRealXmlThatCarriesTheActualWattsReading() {
    // Arrange
    wiremock.stubFor(post("/mirror-usage-points").willReturn(status(201)));

    // Act
    client.post(MirrorUsagePointFactory.build(500_000L));

    // Assert: real marshalled XML, not a JSON body reusing this service's other contract
    wiremock.verify(
        postRequestedFor(urlEqualTo("/mirror-usage-points"))
            .withHeader("Content-Type", containing("xml"))
            .withRequestBody(containing("MirrorUsagePoint"))
            .withRequestBody(containing("<value>500000</value>")));
  }
}
