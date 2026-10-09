package io.arcnode.dercontrol.derevent;

/**
 * The utility's DERPrograms this site is enrolled in, and what each one is for.
 *
 * <p>IEEE 2030.5 carries no reason on a DERControl; the DERProgram an event belongs to is how the
 * spec expresses purpose, and {@code primacy} is how two programs' controls are ranked when they
 * overlap (lower wins, then the later creationTime). The utility names the program in each
 * Notification's {@code subscribedResource}, which is the resource this service subscribed to.
 *
 * <p>Primacy is held here rather than read from the utility's DERProgramList — an MVP shortcut; a
 * real client would subscribe to the list and track changes.
 */
public enum DerProgram {
  /** A conductor near its rating — the utility sends an envelope, never a setpoint. */
  DLR_LINE_CONSTRAINT("/derp/1/derc", 0),
  /** The contracted flex program — the utility commands the enrolled depth as a setpoint. */
  ERCOT_FLEX("/derp/2/derc", 1);

  private final String path;
  private final int primacy;

  DerProgram(String path, int primacy) {
    this.path = path;
    this.primacy = primacy;
  }

  /** The program's resource path, relative to the utility's base URL. */
  public String path() {
    return path;
  }

  /** Relative rank when programs overlap: lower wins, per IEEE 2030.5. */
  public int primacy() {
    return primacy;
  }

  /**
   * The program a Notification came from, by the resource it names.
   *
   * @param subscribedResource the Notification's subscribedResource, an absolute URI
   * @throws IllegalArgumentException when it names a program this site never subscribed to
   */
  public static DerProgram fromSubscribedResource(String subscribedResource) {
    // Reason: suffix match. The utility names the resource with its own absolute base URL, which
    // this service configures but does not own; the program path is the part that is ours.
    for (DerProgram program : values()) {
      if (subscribedResource != null && subscribedResource.endsWith(program.path)) {
        return program;
      }
    }
    throw new IllegalArgumentException(
        "Notification names a DERProgram this site is not subscribed to: " + subscribedResource);
  }
}
