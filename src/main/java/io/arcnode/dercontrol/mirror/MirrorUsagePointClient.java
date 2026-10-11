package io.arcnode.dercontrol.mirror;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.mirror.ieee20305.MirrorUsagePointElement;
import io.arcnode.dercontrol.utility.UtilityTls;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * POSTs a real IEEE 2030.5 {@code MirrorUsagePoint} to the utility's own intake — the outbound side
 * of the compliance path. The exact URI path ({@code /mirror-usage-points}) is NOT a spec-mandated
 * value — real 2030.5 servers publish their own resource URIs via {@code DeviceCapability}
 * discovery, not a fixed path. This is a POC-stage fixed contract between two services we both
 * control, not a claim that this path is itself part of the standard.
 */
@Service
public class MirrorUsagePointClient {

  private static final String PATH = "/mirror-usage-points";

  private final RestClient client;

  public MirrorUsagePointClient(RestClient.Builder builder, Config config, UtilityTls tls) {
    // Reason: UtilityTls keeps the HTTP/2-incapable SimpleClientHttpRequestFactory pin every
    // outbound client in this system relies on (the JDK HttpClient-backed default has a confirmed,
    // reproducible EOFException against WireMock — see mock-derms-dispatch-api's DerEventsClient)
    // and adds the site's client certificate + utility trust when platform has mounted them.
    this.client =
        builder.requestFactory(tls.requestFactory()).baseUrl(config.utilityMirrorUrl()).build();
  }

  public void post(MirrorUsagePointElement usagePoint) {
    String xml = Ieee20305Xml.marshal(usagePoint);
    client
        .post()
        .uri(PATH)
        .contentType(MediaType.APPLICATION_XML)
        .body(xml)
        .retrieve()
        .toBodilessEntity();
  }
}
