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

/** IEEE 2030.5 DERControl ingest over real HTTP against real Postgres + a real broker. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class DerEventResourceIT extends AbstractBrokerIT {

  // Reason: EventStatus.currentStatus codes, per sep.xsd.
  private static final int ACTIVE = 1;
  private static final int COMPLETED = 5;

  /** interval is mandatory on Event, and JAXB unmarshalling alone will not reject its absence. */
  private static final String NO_INTERVAL =
      """
      <Notification schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
      <subscribedResource>https://utility.invalid/derp/1/derc</subscribedResource>\
      <Resource xsi:type="DERControlList" all="1" results="1"\
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><DERControl>\
      <mRID>000000000000000000000000000000ff</mRID><creationTime>1789221600</creationTime>\
      <EventStatus><currentStatus>1</currentStatus><dateTime>1789221600</dateTime>\
      <potentiallySuperseded>false</potentiallySuperseded></EventStatus>\
      </DERControl></Resource><status>0</status>\
      <subscriptionURI>https://utility.invalid/sub/1</subscriptionURI></Notification>""";

  @LocalServerPort int port;
  RestTestClient rest;

  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void postThenGetByMridRoundTrips() {
    // Arrange
    String mrid = SepXml.mrid("post-get");

    // Act: create
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
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
    String mrid = SepXml.mrid("completed");
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
        .exchange()
        .expectStatus()
        .isCreated();

    // Act: retransmit the same mrid with eventStatus=COMPLETED
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, COMPLETED, SepXml.NO_SETPOINT))
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
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(NO_INTERVAL)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void rejectsMissingClientCertHeaderWith400() {
    // Arrange: der-control-ingress always sets this in prod — a request without it never got
    // through the gateway's own cert check, so this can only happen hitting the app directly.
    String mrid = SepXml.mrid("no-cert");

    // Act / Assert
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
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
    String mrid = SepXml.mrid("list-active");
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
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
