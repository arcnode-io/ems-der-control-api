package io.arcnode.dercontrol;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Shared test fixture — a throwaway self-signed cert, not a real device identity. */
public final class TestCerts {

  // keytool -genkeypair -alias test -keyalg RSA -keysize 2048 -validity 3650
  //   -dname "CN=test-device,O=arcnode" -storetype PKCS12
  public static final String PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIC8zCCAdugAwIBAgIIAniEG/UyCMQwDQYJKoZIhvcNAQELBQAwKDEQMA4GA1UE
      ChMHYXJjbm9kZTEUMBIGA1UEAxMLdGVzdC1kZXZpY2UwHhcNMjYwOTEwMjMwNDE5
      WhcNMzYwOTA3MjMwNDE5WjAoMRAwDgYDVQQKEwdhcmNub2RlMRQwEgYDVQQDEwt0
      ZXN0LWRldmljZTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAORvl+Xp
      Xig6zAANwNNPz52zMYURADHJSnXGTAXjNx7xldEGSTcoieWp5s0hQ6lXEVCSvOUd
      4XBR/Aj2CTImdEqqmxOToHWLZ/BOKC3AYztWR2c+wbqs5BP3WcppKoqzEfFDxFMY
      2Ia8sFYa00+LN8kvXyxGc0hx61lLJW+1+C3m4X+5Hycb/g9W8ArxXj3cBVLRDzt3
      mCof9yAtvgK+FyXZKcT8Uqi+0/95Lfj0g8wKb4QhGCBhEJUaU1U62SlIbAeFnch1
      H06yYEeEGm16+0blVQUbhzTR7pt7GcKwtgWyJolOGiuxhNzdN7p81gUko4yI1C+7
      vTa5AjX9H/GIc6MCAwEAAaMhMB8wHQYDVR0OBBYEFGRO5qjATOmQv7Eyb9YSRPuh
      AsOmMA0GCSqGSIb3DQEBCwUAA4IBAQBz4AeqTRCkCXYxGnwIarUk0TUTqBm+9LMG
      MyMfZR8JRnHol9cEnGcn0z+oD4qcU9sZZd+kiGPJ4zJ1U17He8rF4eRk9yL6emBe
      plitZ6o0kFVLM6kBXWa+VdwAy7X+gLoHTp7S30sN7IpmlaTOfMEYaCYJdwaOh8p3
      rWUMqYg5HHxnfXt/SsDkVfxY8uIr/3tKWDKKnN08bQ2/maIFYJXm5RzUu34OMZ/B
      Op7/cKwsO+Um/EjAWsHG7l/wdB5p55nf97lIbITkY9GDaUzcKyCIdYf4oM2g20fq
      9cEraqexMg1Q+BfL3SWV7rN6UByBaPEc/84aswxgdfSMqGMuUA4I
      -----END CERTIFICATE-----
      """;

  /** Independently computed (openssl + a standalone script, not this repo's own code). */
  public static final String LFDI = "91bc15c726ca32fb93245d529753fe351156b265";

  public static final long SFDI = 391203626107L;

  /**
   * {@code X-SSL-Client-Cert} header value — PEM as nginx's {@code $ssl_client_escaped_cert} would
   * present it.
   */
  public static final String HEADER_VALUE = URLEncoder.encode(PEM, StandardCharsets.UTF_8);

  // openssl req -x509 -newkey rsa:2048 -nodes -subj "/CN=unlisted-device/O=arcnode" -days 3650
  /** A second throwaway cert: valid, but its LFDI is on no allowlist. */
  public static final String UNLISTED_PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIDOTCCAiGgAwIBAgIUJ3U6hH3M6+ilih+VH9XIOds6ibgwDQYJKoZIhvcNAQEL
      BQAwLDEYMBYGA1UEAwwPdW5saXN0ZWQtZGV2aWNlMRAwDgYDVQQKDAdhcmNub2Rl
      MB4XDTI2MTAxMDIxMDIyOFoXDTM2MTAwNzIxMDIyOFowLDEYMBYGA1UEAwwPdW5s
      aXN0ZWQtZGV2aWNlMRAwDgYDVQQKDAdhcmNub2RlMIIBIjANBgkqhkiG9w0BAQEF
      AAOCAQ8AMIIBCgKCAQEAr/bxJaiytoSdL9WyHyryL1Hsd1hHkHya8R7eIW/RvdS1
      6Y6b09P4BRW730hIpM6IE1TqApEgmOW3L+TAWYCZZZoIs3BXdIyJElxWgMCsCQhi
      sikUqIUshBz31ZbDb0/mfHpZaJBE3vmPrYskvE8zNyaCz24NmUjEnFKvZ/CwaBkh
      uVKSMhJRyEKQQ2bqirv23f0bcJtUPPFR3EpLYLGRJBcuXWNIps8wMkKA48wzif2R
      KGJfmIh9kpTnER9bN0ZMcGcBtEzcA+KvdLbSR9M2xRVtOt+I3zihIOwhSTXwEDrM
      E2IOfuH5pokgO4JmVio74ff4mjOV4SMRA9uOvzPfQQIDAQABo1MwUTAdBgNVHQ4E
      FgQUrwrhZZdb770E1GP9CxIo++opwIswHwYDVR0jBBgwFoAUrwrhZZdb770E1GP9
      CxIo++opwIswDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAqXUt
      WKOpBgjhlEr4ddi7B/n59lzR0S8QjyQQnNUqv1Kq+SvZsEDa/ED+3+orhIKtkllL
      9jrDQEr3vv70NMubn70Sxa7X6dZco5l/U5mmGM39whbG3QxC3/uoOxifJ6Bci0cU
      faQaoL1VbWNFs/MdWSpHMNvH0eZR0FrCekX5FJ278he6Ybzej/AX1VggzlxSAt3Y
      OVkHgYL3zPaoPECLi0ATIIskb9KuZtU6bQ9hVdUkvLZmyXVpwK26zinq2geMOqx5
      OyQzCF1AKc+jXPrLeQBJhEly4+wepBgpg2BdfNebI+5NDd+sPk6P9G1yi0i+JCTY
      QFjRIfnLeaNPrIHUXg==
      -----END CERTIFICATE-----
      """;

  /** openssl x509 -outform DER | sha256sum | cut -c1-40 — not this repo's own derivation. */
  public static final String UNLISTED_LFDI = "5018c2e392b998ccdef04b943040f3de77b4af7b";

  public static final String UNLISTED_HEADER_VALUE =
      URLEncoder.encode(UNLISTED_PEM, StandardCharsets.UTF_8);

  private TestCerts() {}
}
