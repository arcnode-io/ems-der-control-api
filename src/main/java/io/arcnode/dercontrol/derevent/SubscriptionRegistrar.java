package io.arcnode.dercontrol.derevent;

import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.mirror.Ieee20305Xml;
import io.arcnode.dercontrol.mirror.ieee20305.SubscriptionElement;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Tells the utility where to push DERControl Notifications, by registering this service's own IEEE
 * 2030.5 {@code Subscription}. Until it succeeds the utility has no destination and will not send
 * anything, so this keeps trying rather than failing the boot — a utility that is not up yet is an
 * ordinary condition, not a startup error.
 *
 * <p>This is the client half of the subscription push: the utility never holds an address for this
 * service, it only knows the {@code notificationURI} named here.
 */
@Component
public class SubscriptionRegistrar {

  /** The media type IANA registers for IEEE 2030.5 (published specification: IEEE 2030.5). */
  private static final String SEP_XML = "application/sep+xml";

  private static final Logger LOG = LoggerFactory.getLogger(SubscriptionRegistrar.class);
  private static final long RETRY_MILLIS = 30_000L;
  private static final String SUBSCRIPTION_PATH = "/sub";

  /** Where DERControl Notifications are delivered — this service's own ingest. */
  private static final String NOTIFICATION_PATH = "/der-events";

  /**
   * The utility resource whose changes are being subscribed to. 2030.5 discovers resources by
   * following links rather than by fixed paths, so this path is a convention between these two
   * services; the utility echoes it back in the Notification rather than resolving it.
   */
  private static final String SUBSCRIBED_RESOURCE_PATH = "/derp/1/derc";

  private static final String SCHEMA_VERSION = "2.2";

  /** Subscription::encoding 0 = application/sep+xml, per sep.xsd. */
  private static final short ENCODING_SEP_XML = 0;

  /** sep.xsd: "the preferred schema and extensibility level indication such as +S2". */
  private static final String LEVEL = "+S2";

  /** One DERControl per Notification, so the list limit is 1. */
  private static final long LIMIT = 1L;

  private final RestClient client;
  private final String utilityBaseUrl;
  private final String publicBaseUrl;
  private final AtomicBoolean registered = new AtomicBoolean();

  public SubscriptionRegistrar(RestClient.Builder builder, Config config) {
    // Reason: same HTTP/2-incapable pin as MirrorUsagePointClient, against the same host.
    this.client = builder.requestFactory(new SimpleClientHttpRequestFactory()).build();
    this.utilityBaseUrl = config.utilityMirrorUrl();
    this.publicBaseUrl = config.publicBaseUrl();
  }

  /** Attempts registration, then stops. Runs immediately at startup and retries until it lands. */
  @Scheduled(fixedDelay = RETRY_MILLIS)
  public void register() {
    if (registered.get()) {
      return;
    }
    try {
      client
          .post()
          .uri(utilityBaseUrl + SUBSCRIPTION_PATH)
          .contentType(MediaType.parseMediaType(SEP_XML))
          .body(Ieee20305Xml.marshal(subscription()))
          .retrieve()
          .toBodilessEntity();
      registered.set(true);
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "🔔 Subscribed to {} — the utility will push DERControl Notifications to {}",
            utilityBaseUrl + SUBSCRIBED_RESOURCE_PATH,
            publicBaseUrl + NOTIFICATION_PATH);
      }
    } catch (RuntimeException e) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "⚠️ Could not register a Subscription with {} ({}) — retrying. Until this lands the"
                + " utility has nowhere to push a DERControl.",
            utilityBaseUrl,
            e.toString());
      }
    }
  }

  /** Package-visible so a test can assert the document without going over HTTP. */
  SubscriptionElement subscription() {
    SubscriptionElement subscription = new SubscriptionElement();
    subscription.setSchemaVer(SCHEMA_VERSION);
    subscription.setSubscribedResource(utilityBaseUrl + SUBSCRIBED_RESOURCE_PATH);
    subscription.setNotificationURI(publicBaseUrl + NOTIFICATION_PATH);
    subscription.setEncoding(ENCODING_SEP_XML);
    subscription.setLevel(LEVEL);
    subscription.setLimit(LIMIT);
    return subscription;
  }
}
