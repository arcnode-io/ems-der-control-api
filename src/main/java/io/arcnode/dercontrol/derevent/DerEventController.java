package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.derevent.dto.DerControlRequest;
import io.arcnode.dercontrol.derevent.dto.DerEventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for utility/aggregator DERControl ingest. Thin — all logic lives in {@link
 * DerEventService}. {@code X-SSL-Client-Cert} is set by the der-control-ingress gateway, which
 * already rejected anything without a CA-signed cert — this only derives identity for audit, it
 * doesn't re-check trust.
 */
@Tag(name = "der-events")
@RestController
@RequestMapping("/der-events")
public class DerEventController {

  /** The media type IANA registers for IEEE 2030.5 (published specification: IEEE 2030.5). */
  private static final String SEP_XML = "application/sep+xml";

  private final DerEventService service;

  public DerEventController(DerEventService service) {
    this.service = service;
  }

  @Operation(
      summary = "Ingest a DERControl event",
      description =
          "Accepts an IEEE 2030.5 Notification carrying a DERControl, as application/sep+xml —"
              + " the media type IANA registers for IEEE 2030.5. sep.xsd documents this delivery"
              + " directly: a subscribed resource may be pushed to the subscriber by putting the"
              + " full representation in the Notification's Resource slot under an xsi:type."
              + " Persists the control and republishes the setpoint onto the arcnode MQTT bus. A"
              + " re-transmitted mRID (status change, cancellation) updates the existing event"
              + " rather than duplicating it.")
  @PostMapping(consumes = {SEP_XML, MediaType.APPLICATION_XML_VALUE})
  @ResponseStatus(HttpStatus.CREATED)
  public DerEventResponse ingest(
      @RequestBody String document,
      @Parameter(
              description =
                  "Verified client cert forwarded by der-control-ingress (URL-encoded PEM);"
                      + " device identity (LFDI/SFDI) is derived from it for audit.",
              hidden = true)
          @RequestHeader("X-SSL-Client-Cert")
          String clientCertHeader) {
    DerControlRequest request;
    try {
      request = DerControlNotificationParser.parse(document);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
    }
    // Reason: the document is stored exactly as it arrived, so fields this service does not model
    // (creationTime, the subscription URIs) are still on record for an audit.
    return service.ingest(request, document, clientCertHeader);
  }

  @Operation(summary = "Fetch one persisted DERControl event by mRID")
  @GetMapping("/{mrid}")
  public DerEventResponse findOne(@PathVariable String mrid) {
    return service.findByMrid(mrid).orElseThrow(DerEventController::notFound);
  }

  @Operation(summary = "List persisted DERControl events by lifecycle status")
  @GetMapping
  public List<DerEventResponse> findByStatus(@RequestParam DerControlStatus status) {
    return service.findByStatus(status);
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
}
