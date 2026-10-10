package io.arcnode.dercontrol;

import io.arcnode.dercontrol.derevent.DispatchMode;
import io.arcnode.dercontrol.eventlog.EventLogService;
import java.time.Instant;
import java.util.List;
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
  @Autowired EventLogService eventLog;
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

  @Test
  void beforeCursorPagesNewestFirstAndTypesFilters() {
    // Arrange
    // Reason: the tests in this class run back to back; a window that reaches into the past
    // would pick up the previous test's rows.
    Instant start = Instant.now();
    eventLog.operatorReserveSet(1.0);
    eventLog.dispatchModeSet(DispatchMode.MANUAL);
    eventLog.operatorReserveSet(2.0);
    long newest =
        rest.get()
            .uri("/events?since={since}&limit=10", start)
            .header("Authorization", TestJwt.bearer("viewer"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(EventRow[].class)
            .returnResult()
            .getResponseBody()[2]
            .id();

    // Act + Assert: the page below the newest row, newest first, reserve rows only
    rest.get()
        .uri(
            "/events?since={since}&before={before}&types=OPERATOR_RESERVE_SET&limit=10",
            start,
            newest)
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(1)
        .jsonPath("$[0].value")
        .isEqualTo(1.0);
    // and without the type filter, both older rows, newest first
    rest.get()
        .uri("/events?since={since}&before={before}&limit=10", start, newest)
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[0].type")
        .isEqualTo("DISPATCH_MODE_SET")
        .jsonPath("$[1].type")
        .isEqualTo("OPERATOR_RESERVE_SET");
  }

  @Test
  void untilBoundsTheRangeInclusivelyAndProgramFilters() {
    // Arrange: one DER event (DLR program), then an operator row after it
    // Reason: the tests in this class run back to back; a window that reaches into the past
    // would pick up the previous test's rows.
    Instant start = Instant.now();
    String mrid = SepXml.mrid("filters");
    rest.post()
        .uri("/der-events")
        .contentType(MediaType.parseMediaType(SepXml.MEDIA_TYPE))
        .header("X-SSL-Client-Cert", TestCerts.HEADER_VALUE)
        .body(SepXml.notification(mrid, ACTIVE, SepXml.TARGET_MINUS_1_5MW))
        .exchange()
        .expectStatus()
        .isCreated();
    EventRow[] derRows =
        rest.get()
            .uri(
                "/events?since={since}&program=DLR_LINE_CONSTRAINT&types=DER_EVENT_RECEIVED&limit=10",
                start)
            .header("Authorization", TestJwt.bearer("viewer"))
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(EventRow[].class)
            .returnResult()
            .getResponseBody();
    eventLog.operatorReserveSet(3.0);

    // Act + Assert: the other program sees nothing; until at the DER row excludes the later one
    rest.get()
        .uri("/events?since={since}&program=ERCOT_FLEX&limit=10", start)
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(0);
    rest.get()
        .uri("/events?since={since}&until={until}&limit=10", start, derRows[0].occurredAt())
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$[?(@.type == 'OPERATOR_RESERVE_SET' && @.value == 3.0)]")
        .isEmpty()
        .jsonPath("$[?(@.subject == '%s')].type".formatted(mrid))
        .isNotEmpty();
  }

  @Test
  void retentionIsReportedFromConfig() {
    // Arrange: cfg.yml's local block says 90 days

    // Act + Assert
    rest.get()
        .uri("/events/retention")
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.days")
        .isEqualTo(90);
  }

  @Test
  void orderDescGivesTheNewestPageWithoutACursor() {
    // Arrange: three rows, no cursor yet — the page a history view opens on
    Instant start = Instant.now();
    eventLog.operatorReserveSet(10.0);
    eventLog.dispatchModeSet(DispatchMode.AUTO);
    eventLog.operatorReserveSet(20.0);

    // Act + Assert: newest first, and a bad order value is refused
    rest.get()
        .uri("/events?since={since}&order=desc&limit=2", start)
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.length()")
        .isEqualTo(2)
        .jsonPath("$[0].value")
        .isEqualTo(20.0)
        .jsonPath("$[1].type")
        .isEqualTo("DISPATCH_MODE_SET");
    rest.get()
        .uri("/events?order=sideways")
        .header("Authorization", TestJwt.bearer("viewer"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  /** The row fields this test reads back; a plain record, deserialized from the response. */
  record EventRow(long id, Instant occurredAt, String type) {}
}
