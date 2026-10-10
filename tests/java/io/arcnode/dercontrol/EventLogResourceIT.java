package io.arcnode.dercontrol;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The site's event history over real HTTP: what a DERControl ingest leaves behind, read back the
 * way the HMI reads it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class EventLogResourceIT extends AbstractBrokerIT {

  // Reason: EventStatus.currentStatus code, per sep.xsd.
  private static final int ACTIVE = 1;

  @LocalServerPort int port;
  RestTestClient rest;

  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void anIngestedEventShowsUpAsReceivedThenPostured() {
    // Arrange: one DERControl from the utility, after a known instant
    Instant before = Instant.now().minusSeconds(1);
    String mrid = SepXml.mrid("event-log");
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
        .exchange()
        .expectStatus()
        .isCreated();

    // Act + Assert: the history since that instant, oldest first
    rest.get()
        .uri("/events?since={since}&limit=10", before)
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[?(@.subject == '%s')].type".formatted(mrid))
        .isEqualTo(List.of("DER_EVENT_RECEIVED", "DER_EVENT_STATE"))
        .jsonPath("$[?(@.subject == '%s' && @.type == 'DER_EVENT_RECEIVED')].actor".formatted(mrid))
        .isEqualTo(List.of(TestCerts.LFDI))
        .jsonPath("$[?(@.subject == '%s' && @.type == 'DER_EVENT_STATE')].state".formatted(mrid))
        .isNotEmpty();
  }

  @Test
  void readingWithoutATokenIsUnauthorized() {
    // Arrange: no Authorization header at all

    // Act + Assert
    rest.get().uri("/events").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void aTokenSignedWithAnotherSecretIsUnauthorized() {
    // Arrange: right shape, wrong key

    // Act + Assert
    rest.get()
        .uri("/events")
        .header("Authorization", TestJwt.bearerSignedWithWrongSecret("operator"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }
}
