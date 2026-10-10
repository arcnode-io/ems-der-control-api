package io.arcnode.dercontrol.derevent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The LFDIs allowed to POST DERControls — the per-site answer to "which of the certs the ingress
 * truststore trusts may actually dispatch here". The truststore is CA-level: one shared 2030.5 PKI
 * root admits every aggregator chained to it, so a listed identity is required on top. Read once at
 * boot from {@code DER_CONTROL_LFDI_ALLOWLIST}, which ops fills in the same step as the truststore
 * secret; empty means nobody, and a restart applies a change.
 */
@Component
public class LfdiAllowlist {

  private static final Logger LOG = LoggerFactory.getLogger(LfdiAllowlist.class);

  private final Set<String> lfdis;

  /**
   * @param raw the variable's value — LFDIs separated by commas or any whitespace, any case. The
   *     cloud path collapses newlines to spaces before the value reaches the container.
   */
  public LfdiAllowlist(@Value("${DER_CONTROL_LFDI_ALLOWLIST:}") String raw) {
    this.lfdis =
        Arrays.stream(raw.split("[,\\s]+"))
            .filter(entry -> !entry.isBlank())
            .map(entry -> entry.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    if (lfdis.isEmpty()) {
      LOG.warn("⛔ DER_CONTROL_LFDI_ALLOWLIST is empty: every DERControl will be refused");
    } else if (LOG.isInfoEnabled()) {
      LOG.info("🔐 {} LFDI(s) allowed to POST DERControls", lfdis.size());
    }
  }

  /**
   * @param lfdi hex LFDI as {@link io.arcnode.dercontrol.ClientIdentity} derives it
   * @return whether that identity may dispatch here
   */
  public boolean allows(String lfdi) {
    return lfdis.contains(lfdi.toLowerCase(Locale.ROOT));
  }

  public boolean isEmpty() {
    return lfdis.isEmpty();
  }
}
