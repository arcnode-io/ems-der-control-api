package io.arcnode.dercontrol;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.arcnode.dercontrol.derevent.SubscriptionRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Registering this service's own Subscription with a stubbed utility. This is how the utility
 * learns where to push DERControl Notifications — it holds no configured address for this service —
 * so the document has to carry a notificationURI reachable from the utility's side.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class SubscriptionRegistrarIT extends AbstractBrokerIT {

  @RegisterExtension
  static WireMockExtension wiremock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("app.utilityMirrorUrl", wiremock::baseUrl);
    registry.add("app.publicBaseUrl", () -> "https://site.invalid");
  }

  @Autowired SubscriptionRegistrar registrar;

  @Test
  void postsASubscriptionNamingWhereTheUtilityShouldPush() {
    // Arrange
    wiremock.stubFor(post("/sub").willReturn(status(201)));

    // Act
    registrar.register();

    // Assert: the mandatory Subscription fields, and the destination the utility will use
    wiremock.verify(
        postRequestedFor(urlEqualTo("/sub"))
            .withHeader("Content-Type", containing("application/sep+xml"))
            .withRequestBody(
                containing("<notificationURI>https://site.invalid/der-events</notificationURI>"))
            // encoding 0 = application/sep+xml, per sep.xsd
            .withRequestBody(containing("<encoding>0</encoding>"))
            .withRequestBody(containing("<limit>1</limit>")));
  }

  @Test
  void renewsOnEveryTickSoAUtilityRestartRecovers() {
    // Arrange
    wiremock.stubFor(post("/sub").willReturn(status(201)));
    registrar.register();
    wiremock.resetRequests();

    // Act: the renewal tick fires again
    registrar.register();

    // Assert: the Subscription lives in the utility's own memory, so it has to be renewed rather
    // than registered once. Registering once leaves this site orphaned the moment the utility
    // restarts — it pushes nothing and nothing here notices, because a dispatch that is never
    // delivered looks exactly like a utility with nothing to dispatch.
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo("/sub")))).hasSize(1);
  }

  @Test
  void keepsTryingWhenTheUtilityIsNotUpYet() {
    // Arrange: a utility that is not up is an ordinary condition, not a startup failure
    wiremock.stubFor(post("/sub").willReturn(status(503)));

    // Act
    registrar.register();
    wiremock.resetRequests();
    registrar.register();

    // Assert: still unregistered, so it tried again
    assertThat(wiremock.findAll(postRequestedFor(urlEqualTo("/sub")))).hasSize(1);
  }
}
