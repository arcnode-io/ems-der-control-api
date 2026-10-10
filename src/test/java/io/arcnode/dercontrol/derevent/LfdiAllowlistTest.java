package io.arcnode.dercontrol.derevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Unit — parsing the allowlist out of its one environment variable. AAA. */
class LfdiAllowlistTest {

  private static final String LISTED = "0c6937d4d21a5675a51092e51d9e54e19bff55ae";
  private static final String OTHER = "91bc15c726ca32fb93245d529753fe351156b265";

  @Test
  void anEmptyListAllowsNobody() {
    // Arrange
    LfdiAllowlist allowlist = new LfdiAllowlist("");

    // Act + Assert
    assertThat(allowlist.allows(LISTED)).isFalse();
    assertThat(allowlist.isEmpty()).isTrue();
  }

  @Test
  void entriesSplitOnCommasAndWhitespaceAndIgnoreCase() {
    // Arrange: the ops path collapses newlines to spaces and people type hex either way
    LfdiAllowlist allowlist =
        new LfdiAllowlist(" \n" + LISTED.toUpperCase(Locale.ROOT) + ",  " + OTHER + " ");

    // Act + Assert
    assertThat(allowlist.allows(LISTED)).isTrue();
    assertThat(allowlist.allows(OTHER.toUpperCase(Locale.ROOT))).isTrue();
    assertThat(allowlist.isEmpty()).isFalse();
  }

  @Test
  void anUnlistedLfdiIsRefused() {
    // Arrange
    LfdiAllowlist allowlist = new LfdiAllowlist(LISTED);

    // Act + Assert
    assertThat(allowlist.allows(OTHER)).isFalse();
  }

  @Test
  void thePlaceholderOfZerosAllowsNobodyReal() {
    // Arrange: platform-api's Secrets Manager placeholder, since an empty SecretString is refused
    LfdiAllowlist allowlist = new LfdiAllowlist("0".repeat(40));

    // Act + Assert
    assertThat(allowlist.allows(LISTED)).isFalse();
  }
}
