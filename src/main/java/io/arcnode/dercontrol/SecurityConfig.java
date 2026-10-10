package io.arcnode.dercontrol;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may read what over HTTP. Only the HMI-facing history read needs a caller to prove anything
 * here: the DERControl ingest is authenticated by mutual TLS at der-control-ingress, which hands
 * the verified cert down as a header, and the rest is diagnostics on a loopback-only port.
 *
 * <p>The HMI's token is minted by device-api: HS256 over {@code AUTH_JWT_SECRET}, which both
 * services therefore hold. Verifying it here is the same check device-api makes, not a second
 * identity scheme.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain httpSecurity(HttpSecurity http) throws Exception {
    http
        // Reason: a stateless API with no browser session has nothing for CSRF to protect, and
        // the check would reject the utility's POST /der-events outright.
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers("/events/**").authenticated().anyRequest().permitAll())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
    return http.build();
  }

  /**
   * Verifies HS256 tokens signed with the secret device-api signs them with.
   *
   * @param secret {@code AUTH_JWT_SECRET} from the environment, the same value device-api holds
   */
  @Bean
  JwtDecoder jwtDecoder(@Value("${AUTH_JWT_SECRET}") String secret) {
    // Reason: @nestjs/jwt signs with the secret's UTF-8 bytes, so the key here is exactly that.
    SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }
}
