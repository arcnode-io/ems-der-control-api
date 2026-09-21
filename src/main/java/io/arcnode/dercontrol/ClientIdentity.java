package io.arcnode.dercontrol;

import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Device identity derived from the DER-control-ingress nginx gateway's verified client certificate,
 * per IEEE 2030.5 §6.3.4. The gateway terminates mTLS and forwards the cert as the {@code
 * X-SSL-Client-Cert} header ({@code $ssl_client_escaped_cert} — URL-encoded PEM); this never does
 * its own trust/signature check, that's the gateway's job. This only derives identity from a cert
 * the gateway already accepted.
 *
 * @param lfdi Long-Form Device Identifier — the first 20 bytes of SHA-256(cert DER bytes), hex
 * @param sfdi Short-Form Device Identifier — the first 5 LFDI bytes as a 40-bit value,
 *     right-shifted 4 bits to 36 bits, with a decimal check digit appended (makes the full decimal
 *     digit sum a multiple of 10)
 */
public record ClientIdentity(String lfdi, long sfdi) {

  /**
   * @param urlEncodedPemHeader the raw {@code X-SSL-Client-Cert} header value
   * @throws IllegalArgumentException if the header doesn't decode to a parseable X.509 certificate
   */
  public static ClientIdentity fromHeaderValue(String urlEncodedPemHeader) {
    return fromPem(URLDecoder.decode(urlEncodedPemHeader, StandardCharsets.UTF_8));
  }

  /**
   * Same derivation as {@link #fromHeaderValue}, for a plain (not URL-encoded) PEM — this service's
   * own self-signed cert ({@link io.arcnode.dercontrol.mirror.DerDispatchIdentity}), not one
   * presented by a caller.
   *
   * @throws IllegalArgumentException if the PEM doesn't decode to a parseable X.509 certificate
   */
  public static ClientIdentity fromPem(String pem) {
    byte[] lfdiBytes = lfdiBytes(parseCertificate(pem));
    return new ClientIdentity(HexFormat.of().formatHex(lfdiBytes), sfdiFromLfdiBytes(lfdiBytes));
  }

  private static X509Certificate parseCertificate(String pem) {
    try {
      CertificateFactory factory = CertificateFactory.getInstance("X.509");
      return (X509Certificate)
          factory.generateCertificate(
              new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    } catch (CertificateException e) {
      throw new IllegalArgumentException("invalid client certificate", e);
    }
  }

  private static byte[] lfdiBytes(X509Certificate cert) {
    try {
      MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
      return Arrays.copyOf(sha256.digest(cert.getEncoded()), 20);
    } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
      throw new IllegalStateException("failed to hash client certificate", e);
    }
  }

  /** Package-visible for the check-digit math's own unit test. */
  static long sfdiFromLfdiBytes(byte[] lfdiBytes) {
    long first40 = 0;
    for (int i = 0; i < 5; i++) {
      first40 = (first40 << 8) | (lfdiBytes[i] & 0xFF);
    }
    long value36 = first40 >>> 4;
    return value36 * 10 + checkDigit(value36);
  }

  private static int checkDigit(long value) {
    int digitSum = 0;
    for (long v = value; v > 0; v /= 10) {
      digitSum += (int) (v % 10);
    }
    return (10 - (digitSum % 10)) % 10;
  }
}
