package io.arcnode.dercontrol.mirror;

import io.arcnode.dercontrol.ClientIdentity;

/**
 * This service's own identity when reporting {@code der_dispatch}'s measurements as a {@code
 * MirrorUsagePoint} — the {@code deviceLFDI} identifies the device being mirrored, i.e. us, not a
 * caller. Same self-signed-cert pattern as mock-derms-dispatch-api's own {@code
 * MockUtilityIdentity} (that service's identity when it calls us); this is the mirror image, for
 * when we call out to it.
 *
 * @see <a href="https://gitlab.com/arcnode-io/mock-derms-dispatch-api">mock-derms-dispatch-api's
 *     MockUtilityIdentity for the identical generation method</a>
 */
public final class DerDispatchIdentity {

  // keytool -genkeypair -alias der-dispatch -keyalg RSA -keysize 2048 -validity 3650
  //   -dname "CN=der-control-api,O=arcnode" -storetype PKCS12
  private static final String PEM =
      """
      -----BEGIN CERTIFICATE-----
      MIIC/DCCAeSgAwIBAgIJAItwS+1tSAUnMA0GCSqGSIb3DQEBCwUAMCwxEDAOBgNV
      BAoTB2FyY25vZGUxGDAWBgNVBAMTD2Rlci1jb250cm9sLWFwaTAeFw0yNjA5MjEx
      OTA5MjBaFw0zNjA5MTgxOTA5MjBaMCwxEDAOBgNVBAoTB2FyY25vZGUxGDAWBgNV
      BAMTD2Rlci1jb250cm9sLWFwaTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
      ggEBANNiciy8yo1dyveE1aSD/dD9waLvwsXW/qrlhm1CqBaTumprsUmIzK3rRMkA
      HJPi7GOXK/35YsT1ib4f+7wLtB7y9dYr8TQ8SyYm1MPOV/d66mk0sww7e0mEmzNH
      Pg+Qx7JLMaU6PZ3goEs6uDnW8AElVA/QeoonU8mLHxRn1MR+Ez+kALhh1yHu2VGl
      HHOzTOME5nI/TeIMHGdEb/H8pb52h7uEFiO28B9OTpHsCvcFkl/t84IXuAZ1e0pa
      pIAZBFiQzaCBCeHKErpAaaOWZ3TGUeTXKtF+pCRMGWk+MRuPlFz8u3XcIoJrOEP6
      OvrDcB+F+3NrsZr4zA/dF8Ypc7UCAwEAAaMhMB8wHQYDVR0OBBYEFMxapCuW1Fh+
      oEJsYkIVi7322O2ZMA0GCSqGSIb3DQEBCwUAA4IBAQA/y6ynb/jKuyO3pNSLzecc
      eHEnuQ7XDyejyiHqYxSgy5jLEIupQLBzieM7FrgguFP8nWt50BybzYzs+UpWfOQu
      UlYv7maxg5rSuQOKCu5eeLGlYkETAfv/0bPslRIJmUNdxr574iRPYzQ9tBRBBm2k
      uCTF1IX++zYgr4DgwyxH3+L5fHKhz9zjKvDFtjR5T02eFlkIBCz3Lg5llsCaMFJe
      6m5hIIpyDCoy2/0e3hMLmBW/JOwYhSZ70eISZe/fa3dj80Ux0i6KN1VzHty8pk2/
      432wLS5oXxMjYd5ebGOvATnbrPGgzaGFE1lOoomot2i7Pi1JHj6GdpfmehQR67qY
      -----END CERTIFICATE-----
      """;

  /**
   * der_dispatch's own LFDI, hex — the value that goes in every {@code MirrorUsagePoint} we send.
   */
  public static final String LFDI = ClientIdentity.fromPem(PEM).lfdi();

  private DerDispatchIdentity() {}
}
