package io.arcnode.dercontrol.utility;

import io.arcnode.dercontrol.ClientIdentity;
import io.arcnode.dercontrol.Config;
import io.arcnode.dercontrol.mirror.DerDispatchIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;

/**
 * The outbound TLS identity this site presents to the utility (IEEE 2030.5 §6.3.4 mutual TLS), read
 * once at boot from the three PEM files {@link Config} names. All three absent = the utility is not
 * connected yet: calls go out with no client certificate and JVM trust, one WARN. Any other mix
 * fails boot — a half-mounted identity is a deployment error, not a state.
 *
 * <p>Both utility-facing clients take their request factory from here, so the HTTP/1.1 {@code
 * SimpleClientHttpRequestFactory} pin they rely on is kept while the SSL bundle is applied. The
 * LFDI the utility knows us by is the SHA-256 of the client certificate; without one it falls back
 * to {@link DerDispatchIdentity}'s embedded cert (local and the device demo).
 */
@Component
public final class UtilityTls {

  private static final Logger LOG = LoggerFactory.getLogger(UtilityTls.class);

  private final Optional<SslBundle> bundle;
  private final String lfdi;

  public UtilityTls(Config config) {
    Path cert = Path.of(config.utilityClientCertPath());
    Path key = Path.of(config.utilityClientKeyPath());
    Path ca = Path.of(config.utilityCaBundlePath());
    List<Path> missing = Stream.of(cert, key, ca).filter(p -> !Files.isRegularFile(p)).toList();
    if (missing.size() == 3) {
      if (LOG.isWarnEnabled()) {
        LOG.warn(
            "🔓 utility TLS identity not configured ({} absent): calls to the utility carry no client certificate",
            cert.getParent());
      }
      this.bundle = Optional.empty();
      this.lfdi = DerDispatchIdentity.LFDI;
      return;
    }
    if (!missing.isEmpty()) {
      throw new IllegalStateException("utility TLS identity is incomplete: missing " + missing);
    }
    String certPem = read(cert);
    PemSslStoreDetails keyStore =
        PemSslStoreDetails.forCertificate(certPem).withPrivateKey(read(key));
    PemSslStoreDetails trustStore = PemSslStoreDetails.forCertificates(read(ca));
    this.bundle = Optional.of(SslBundle.of(new PemSslStoreBundle(keyStore, trustStore)));
    this.lfdi = ClientIdentity.fromPem(certPem).lfdi();
    if (LOG.isInfoEnabled()) {
      LOG.info("🔐 utility TLS identity loaded from {}: LFDI {}", cert.getParent(), lfdi);
    }
  }

  /** A fresh HTTP/1.1 request factory carrying the client identity and utility trust, if any. */
  public ClientHttpRequestFactory requestFactory() {
    HttpClientSettings settings =
        bundle.map(HttpClientSettings::ofSslBundle).orElseGet(HttpClientSettings::defaults);
    return ClientHttpRequestFactoryBuilder.simple().build(settings);
  }

  /** The LFDI (hex) this site reports as itself: the client certificate's, or the embedded one. */
  public String lfdi() {
    return lfdi;
  }

  private static String read(Path pem) {
    try {
      return Files.readString(pem);
    } catch (IOException e) {
      throw new IllegalStateException("failed to read " + pem, e);
    }
  }
}
