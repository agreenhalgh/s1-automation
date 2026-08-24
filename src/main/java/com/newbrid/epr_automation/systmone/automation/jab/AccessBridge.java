package com.newbrid.epr_automation.systmone.automation.jab;

import java.util.List;

/**
 * Facade over the Windows Access Bridge C API — the single seam through which the
 * framework touches native accessibility (NFR-MNT-02). Two implementations exist:
 * {@link JnaAccessBridge} (real, via {@code WindowsAccessBridge-64.dll}) and
 * {@link NullAccessBridge} (always unavailable, for non-Windows/dev/test).
 *
 * <p>Keeping this interface small means bot authors and the engine never see native
 * types, and the native layer can be validated in isolation (Risk R1 spike).</p>
 */
public interface AccessBridge {

    /** Whether the bridge initialised and the native DLL is usable on this host. */
    boolean isAvailable();

    /** Top-level accessible contexts for windows whose title contains the given text. */
    List<AcRef> roots(String windowTitleContains);

    /** Decode a node's attributes, or {@code null} if the handle is invalid. */
    AccessibleInfo info(AcRef ref);

    /** The i-th child context, or {@code null}. */
    AcRef child(AcRef ref, int index);

    /** Invoke a named accessible action (e.g. {@code "click"}, {@code "toggle expand"}). */
    boolean doAction(AcRef ref, String action);

    /** Set the text contents of an editable component. */
    boolean setText(AcRef ref, String text);

    /** Read the text contents of a component. */
    String getText(AcRef ref);

    /**
     * How big this table is, or null if the node is not a table or the application will not say.
     *
     * <p>Separate from the tree because a table's contents are frequently not in the tree at all: a
     * Swing {@code JTable} exposes no row or cell children, so walking finds an empty table however
     * many rows it has.</p>
     */
    TableGeometry tableGeometry(AcRef table);

    /**
     * The cell at a row and column of a table, or null.
     *
     * <p>Zero-based, as the table API counts. The returned handle belongs to the caller and must be
     * released like any other.</p>
     */
    AcRef tableCell(AcRef table, int row, int column);

    /** Release a native handle obtained from this bridge. */
    void release(AcRef ref);
}
