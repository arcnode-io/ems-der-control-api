package io.arcnode.dercontrol;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * Test-only TLS material: throwaway self-signed key pairs made by the running JDK's keytool at test
 * time, so no private key is ever committed. One identity per call; PEM renderings match the files
 * der-control-api reads.
 */
public final class TestTls {

  public static final String STORE_PASSWORD = "changeit";

  /** A self-signed identity: the PKCS12 it lives in, its cert, and PEM renderings of cert + key. */
  public record Identity(
      KeyStore keyStore,
      X509Certificate certificate,
      String certificatePem,
      String privateKeyPem) {}

  private TestTls() {}

  /**
   * Generates a 2048-bit RSA self-signed certificate.
   *
   * @param dir where the PKCS12 goes
   * @param name keystore alias and file stem
   * @param dn subject, e.g. {@code CN=127.0.0.1,O=test}
   * @param san keytool SAN extension value such as {@code ip:127.0.0.1}, or null for none
   */
  public static Identity selfSigned(Path dir, String name, String dn, String san)
      throws IOException, GeneralSecurityException, InterruptedException {
    Path p12 = dir.resolve(name + ".p12");
    List<String> cmd = new ArrayList<>(List.of(keytool(), "-genkeypair", "-alias", name));
    cmd.addAll(List.of("-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", dn));
    cmd.addAll(List.of("-storetype", "PKCS12", "-keystore", p12.toString()));
    cmd.addAll(List.of("-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD));
    if (san != null) {
      cmd.addAll(List.of("-ext", "SAN=" + san));
    }
    Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    String out = new String(proc.getInputStream().readAllBytes(), UTF_8);
    if (proc.waitFor() != 0) {
      throw new IllegalStateException("keytool failed: " + out);
    }
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(p12)) {
      ks.load(in, STORE_PASSWORD.toCharArray());
    }
    X509Certificate cert = (X509Certificate) ks.getCertificate(name);
    PrivateKey key = (PrivateKey) ks.getKey(name, STORE_PASSWORD.toCharArray());
    return new Identity(
        ks, cert, pem("CERTIFICATE", cert.getEncoded()), pem("PRIVATE KEY", key.getEncoded()));
  }

  /**
   * An HTTPS server on the loopback address answering "pong" on /ping — only to a client presenting
   * {@code trustedClient} (needClientAuth), which is what a real 2030.5 utility does.
   */
  public static HttpsServer utilityRequiringClientCert(
      Identity server, X509Certificate trustedClient) throws IOException, GeneralSecurityException {
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(server.keyStore(), STORE_PASSWORD.toCharArray());
    KeyStore trust = KeyStore.getInstance("PKCS12");
    trust.load(null, null);
    trust.setCertificateEntry("client", trustedClient);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trust);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    HttpsServer https =
        HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    https.setHttpsConfigurator(
        new HttpsConfigurator(ctx) {
          @Override
          public void configure(HttpsParameters params) {
            SSLParameters ssl = ctx.getDefaultSSLParameters();
            ssl.setNeedClientAuth(true);
            params.setSSLParameters(ssl);
          }
        });
    https.createContext(
        "/ping",
        exchange -> {
          byte[] body = "pong".getBytes(UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    https.start();
    return https;
  }

  private static String pem(String type, byte[] der) {
    String body = Base64.getMimeEncoder(64, "\n".getBytes(UTF_8)).encodeToString(der);
    return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
  }

  private static String keytool() {
    return Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
  }
}
