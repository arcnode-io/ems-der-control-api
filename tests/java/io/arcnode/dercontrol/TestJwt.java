package io.arcnode.dercontrol;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Mints the token device-api mints for the HMI: HS256 over the shared secret, {@code sub} and
 * {@code role} both the role name, short expiry. A plain class, referenced explicitly.
 */
public final class TestJwt {

  private TestJwt() {}

  /** An {@code Authorization} header value for the given role, signed with the IT secret. */
  public static String bearer(String role) {
    return "Bearer " + token(role, AbstractBrokerIT.JWT_SECRET, Instant.now().plusSeconds(300));
  }

  /** A token signed with a secret the service does not hold. */
  public static String bearerSignedWithWrongSecret(String role) {
    return "Bearer "
        + token(
            role, "not-the-secret-the-service-holds-0123456789", Instant.now().plusSeconds(300));
  }

  private static String token(String role, String secret, Instant expiresAt) {
    try {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader(JWSAlgorithm.HS256),
              new JWTClaimsSet.Builder()
                  .subject(role)
                  .claim("role", role)
                  .issueTime(Date.from(Instant.now()))
                  .expirationTime(Date.from(expiresAt))
                  .build());
      jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign a test token", e);
    }
  }
}
