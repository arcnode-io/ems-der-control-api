package io.arcnode.dercontrol.mirror;

import io.arcnode.dercontrol.mirror.ieee20305.KindType;
import io.arcnode.dercontrol.mirror.ieee20305.MRIDType;
import io.arcnode.dercontrol.mirror.ieee20305.MirrorMeterReading;
import io.arcnode.dercontrol.mirror.ieee20305.MirrorUsagePoint;
import io.arcnode.dercontrol.mirror.ieee20305.PowerOfTenMultiplierType;
import io.arcnode.dercontrol.mirror.ieee20305.Reading;
import io.arcnode.dercontrol.mirror.ieee20305.ReadingType;
import io.arcnode.dercontrol.mirror.ieee20305.RoleFlagsType;
import io.arcnode.dercontrol.mirror.ieee20305.ServiceKind;
import io.arcnode.dercontrol.mirror.ieee20305.UomType;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Builds a real IEEE 2030.5 {@code MirrorUsagePoint} reporting der_dispatch's actual delivered
 * active power — the compliance signal a real utility reads back over HTTP, replacing the removed
 * {@code ComplianceTracker}'s direct broker access. Fields verified directly against {@code
 * sep.xsd}, MVP-scoped to only what this specific reading needs (real per-field codes, not every
 * optional field the schema allows).
 */
public final class MirrorUsagePointFactory {

  // Reason: RoleFlagsType bit 0 (isMirror) | bit 3 (isDER) = 0x09, per sep.xsd's own doc comment —
  // "isMirror SHALL be set if the server is not the measurement device" (true: der-control-api
  // reports on the BESS's behalf) and "isDER SHALL be set if the usage applies to a distributed
  // energy resource" (true).
  private static final byte[] ROLE_FLAGS_MIRROR_AND_DER = {0x00, 0x09};
  // Reason: ServiceKind 0 = electricity, per sep.xsd.
  private static final short SERVICE_KIND_ELECTRICITY = 0;
  // Reason: KindType 37 = Power, per sep.xsd's own documented UInt8 codes.
  private static final short KIND_POWER = 37;
  // Reason: UomType 38 = W (real power in watts), per sep.xsd.
  private static final short UOM_WATTS = 38;
  // Reason: raw watts, no scaling — matches target_active_power/actual_active_power's own existing
  // convention elsewhere in this service.
  private static final byte MULTIPLIER_NONE = 0;

  private MirrorUsagePointFactory() {}

  /**
   * @param activeWatts der_dispatch's actual measured active power — positive discharge, negative
   *     charge, matching bess_rack's own {@code active_power} convention
   */
  public static MirrorUsagePoint build(long activeWatts) {
    MirrorUsagePoint usagePoint = new MirrorUsagePoint();
    usagePoint.setDeviceLFDI(HexFormat.of().parseHex(DerDispatchIdentity.LFDI));
    usagePoint.setMRID(mrid(0));
    usagePoint.setRoleFlags(roleFlags());
    usagePoint.setServiceCategoryKind(serviceCategoryKind());
    usagePoint.getMirrorMeterReading().add(activePowerReading(activeWatts));
    return usagePoint;
  }

  // Reason: mRID is spec'd as IANA-PEN-structured (bits 0-31 = provider ID, remaining 96 bits
  // provider-assigned) — ARCNODE has no registered PEN, so these are NOT real PEN-compliant
  // mRIDs, just deterministic 128-bit values (slices of the same SHA-256(cert) LFDI is already
  // derived from) satisfying the schema's structural requirement. Flagged here, not hidden — get
  // a real PEN before this is anything more than a mock. MirrorUsagePoint and each
  // MirrorMeterReading both extend IdentifiedObject independently and each need their own distinct
  // mRID, so this takes a byte offset rather than always using the same 16 bytes.
  private static MRIDType mrid(int offset) {
    byte[] lfdiBytes = HexFormat.of().parseHex(DerDispatchIdentity.LFDI);
    MRIDType mrid = new MRIDType();
    mrid.setValue(Arrays.copyOfRange(lfdiBytes, offset, offset + 16));
    return mrid;
  }

  private static RoleFlagsType roleFlags() {
    RoleFlagsType roleFlags = new RoleFlagsType();
    roleFlags.setValue(ROLE_FLAGS_MIRROR_AND_DER);
    return roleFlags;
  }

  private static ServiceKind serviceCategoryKind() {
    ServiceKind serviceKind = new ServiceKind();
    serviceKind.setValue(SERVICE_KIND_ELECTRICITY);
    return serviceKind;
  }

  private static MirrorMeterReading activePowerReading(long activeWatts) {
    KindType kind = new KindType();
    kind.setValue(KIND_POWER);
    ReadingType readingType = new ReadingType();
    readingType.setKind(kind);
    readingType.setUom(uom());
    readingType.setPowerOfTenMultiplier(multiplierNone());

    Reading reading = new Reading();
    reading.setValue(activeWatts);

    MirrorMeterReading meterReading = new MirrorMeterReading();
    meterReading.setMRID(mrid(4));
    meterReading.setReadingType(readingType);
    meterReading.setReading(reading);
    return meterReading;
  }

  private static UomType uom() {
    UomType uom = new UomType();
    uom.setValue(UOM_WATTS);
    return uom;
  }

  private static PowerOfTenMultiplierType multiplierNone() {
    PowerOfTenMultiplierType multiplier = new PowerOfTenMultiplierType();
    multiplier.setValue(MULTIPLIER_NONE);
    return multiplier;
  }
}
