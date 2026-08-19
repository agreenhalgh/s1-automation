package com.newbrid.epr_automation.systmone.automation.jab;

/**
 * A reference to a node in a Java application's accessibility tree, as exposed by the
 * Windows Access Bridge: the source JVM id plus the 64-bit accessible-context handle.
 */
public record AcRef(int vmId, long ac) {}
