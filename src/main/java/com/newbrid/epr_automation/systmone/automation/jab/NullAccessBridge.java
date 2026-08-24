package com.newbrid.epr_automation.systmone.automation.jab;

import java.util.List;

/**
 * No-op bridge used when the Windows Access Bridge is unavailable (non-Windows host,
 * DLL not present, or Access Bridge not enabled). Reports itself unavailable so the
 * engine skips JAB locators and uses the screen-recognition fallback.
 */
public class NullAccessBridge implements AccessBridge {

    private final String reason;

    public NullAccessBridge(String reason) {
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }

    @Override public boolean isAvailable() { return false; }
    @Override public List<AcRef> roots(String windowTitleContains) { return List.of(); }
    @Override public AccessibleInfo info(AcRef ref) { return null; }
    @Override public AcRef child(AcRef ref, int index) { return null; }
    @Override public boolean doAction(AcRef ref, String action) { return false; }
    @Override public boolean setText(AcRef ref, String text) { return false; }
    @Override public String getText(AcRef ref) { return null; }
    @Override public TableGeometry tableGeometry(AcRef table) { return null; }
    @Override public AcRef tableCell(AcRef table, int row, int column) { return null; }
    @Override public void release(AcRef ref) { }
}
