package io.arcnode.dercontrol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** DERControl ingest over real HTTP against real Postgres + a real broker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DerEventResourceIT extends AbstractBrokerIT {

  private static final String VALID_BODY =
      """
      {
        "mrid": "%s",
        "eventStatus": "ACTIVE",
        "interval": { "start": "2026-09-08T14:00:00Z", "durationSeconds": 3600 },
        "derControlBase": { "opModTargetW": -1500000.0, "opModEnergize": true }
      }
      """;

  @LocalServerPort int port;
  RestTestClient rest;

  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void postThenGetByMridRoundTrips() {
    // Arrange
    String mrid = "mrid-post-get";

    // Act: create
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody()
        .jsonPath("$.mrid")
        .isEqualTo(mrid)
        .jsonPath("$.targetActivePowerW")
        .isEqualTo(-1500000.0)
        .jsonPath("$.submittedByLfdi")
        .isEqualTo(TestCerts.LFDI);

    // Assert: fetch
    rest.get()
        .uri("/der-events/{mrid}", mrid)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("ACTIVE")
        .jsonPath("$.durationSeconds")
        .isEqualTo(3600);
  }

  @Test
  void retransmittingWithCompletedStatusClosesTheEventOverRealHttp() {
    // Arrange: dispatch, then close via natural duration expiry (COMPLETED), not cancellation
    String mrid = "mrid-completed";
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated();

    // Act: retransmit the same mrid with eventStatus=COMPLETED
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(
            """
            {
              "mrid": "%s",
              "eventStatus": "COMPLETED",
              "interval": { "start": "2026-09-08T14:00:00Z", "durationSeconds": 3600 },
              "derControlBase": {}
            }
            """
                .formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated();

    // Assert
    rest.get()
        .uri("/der-events/{mrid}", mrid)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("COMPLETED");
  }

  @Test
  void rejectsBodyMissingIntervalWith400() {
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body("{\"mrid\":\"mrid-bad\",\"eventStatus\":\"ACTIVE\"}")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void rejectsMissingClientCertHeaderWith400() {
    // Arrange: der-control-ingress always sets this in prod — a request without it never got
    // through the gateway's own cert check, so this can only happen hitting the app directly.
    String mrid = "mrid-no-cert";

    // Act / Assert
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void returns404ForUnknownMrid() {
    rest.get().uri("/der-events/{mrid}", "does-not-exist").exchange().expectStatus().isNotFound();
  }

  @Test
  void listsByStatus() {
    // Arrange
    String mrid = "mrid-list-active";
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(VALID_BODY.formatted(mrid))
        .exchange()
        .expectStatus()
        .isCreated();

    // Act
    List<String> mrids =
        rest
            .get()
            .uri("/der-events?status=ACTIVE")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(new ParameterizedTypeReference<List<java.util.Map<String, Object>>>() {})
            .returnResult()
            .getResponseBody()
            .stream()
            .map(m -> (String) m.get("mrid"))
            .toList();

    // Assert
    assertThat(mrids).contains(mrid);
  }
}
