package com.newbrid.epr_automation.systmone.automation.jab;

import com.sun.jna.Library;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.util.List;

/**
 * JNA binding to a subset of the Java Access Bridge native C API exported by
 * {@code WindowsAccessBridge-64.dll} (FR-JAB-01/02/04). This maps the documented
 * {@code AccessBridgeCalls.h} / {@code AccessBridgePackages.h} contract.
 *
 * <p><b>Validation status (Risk R1):</b> the exact native calling convention and
 * struct field alignment can only be confirmed on a Windows host with SystmOne and the
 * Access Bridge enabled. This binding compiles and is the artefact for that spike; it
 * is loaded lazily and guarded (see {@link JnaAccessBridge}) so its absence never
 * breaks the rest of the framework.</p>
 *
 * <p>Type mapping note: the Access Bridge {@code long vmID} is a Windows C {@code long}
 * (32-bit → Java {@code int}); {@code AccessibleContext}/{@code JOBJECT64} is 64-bit
 * (→ Java {@code long}).</p>
 */
public interface WindowsAccessBridgeLib extends Library {

    int MAX_STRING_SIZE = 1024;
    int SHORT_STRING_SIZE = 256;
    int MAX_ACTIONS_TO_DO = 32;

    // ---- lifecycle ----
    void Windows_run();

    // ---- window → context ----
    boolean isJavaWindow(Pointer hwnd);

    boolean getAccessibleContextFromHWND(Pointer hwnd, IntByReference vmID, LongByReference ac);

    // ---- tree traversal / attributes ----
    boolean getAccessibleContextInfo(int vmID, long ac, AccessibleContextInfo info);

    long getAccessibleChildFromContext(int vmID, long ac, int index);

    // ---- actions ----
    boolean getAccessibleActions(int vmID, long ac, AccessibleActions actions);

    boolean doAccessibleActions(int vmID, long ac, AccessibleActionsToDo actionsToDo, IntByReference failure);

    // ---- text ----
    boolean getAccessibleTextInfo(int vmID, long ac, AccessibleTextInfo info, int x, int y);

    boolean getAccessibleTextRange(int vmID, long ac, int start, int end, char[] text, short len);

    boolean setTextContents(int vmID, long ac, char[] text);

    // ---- tables ----

    /**
     * Table geometry: row and column counts, plus the {@code AccessibleTable} handle that cell
     * lookups are made against.
     *
     * <p>Needed because a Swing {@code JTable} does not expose its cells as accessible children —
     * the tree shows a table with nothing in it, and no amount of walking finds a row. The table
     * API is the only way in.</p>
     */
    boolean getAccessibleTableInfo(int vmID, long ac, AccessibleTableInfo info);

    /**
     * One cell of a table, by row and column.
     *
     * @param accessibleTable the {@code accessibleTable} field of {@link AccessibleTableInfo} — not
     *                        the table's {@code AccessibleContext}. They are different handles and
     *                        passing the context here returns nothing.
     */
    boolean getAccessibleTableCellInfo(int vmID, long accessibleTable, int row, int column,
                                       AccessibleTableCellInfo info);

    // ---- resource management ----
    void releaseJavaObject(int vmID, long object);

    // =====================================================================
    // Native structures (documented layouts from AccessBridgePackages.h)
    // =====================================================================

    /** Mirrors {@code AccessibleContextInfo}. */
    class AccessibleContextInfo extends Structure {
        public char[] name = new char[MAX_STRING_SIZE];
        public char[] description = new char[MAX_STRING_SIZE];
        public char[] role = new char[SHORT_STRING_SIZE];
        public char[] role_en_US = new char[SHORT_STRING_SIZE];
        public char[] states = new char[SHORT_STRING_SIZE];
        public char[] states_en_US = new char[SHORT_STRING_SIZE];
        public int indexInParent;
        public int childrenCount;
        public int x;
        public int y;
        public int width;
        public int height;
        public int accessibleComponent;
        public int accessibleAction;
        public int accessibleSelection;
        public int accessibleText;
        public int accessibleInterfaces;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("name", "description", "role", "role_en_US", "states", "states_en_US",
                    "indexInParent", "childrenCount", "x", "y", "width", "height",
                    "accessibleComponent", "accessibleAction", "accessibleSelection",
                    "accessibleText", "accessibleInterfaces");
        }
    }

    /**
     * Mirrors {@code AccessibleTableInfo}. Field order is the struct's, not a tidier one: JNA lays
     * the fields out in declaration order and a rearrangement reads the wrong bytes.
     */
    class AccessibleTableInfo extends Structure {
        /** AccessibleContext of the caption, or 0. */
        public long caption;
        /** AccessibleContext of the summary, or 0. */
        public long summary;
        public int rowCount;
        public int columnCount;
        /** The table's own AccessibleContext. */
        public long accessibleContext;
        /** The AccessibleTable handle, which is what cell lookups take. */
        public long accessibleTable;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("caption", "summary", "rowCount", "columnCount", "accessibleContext",
                    "accessibleTable");
        }
    }

    /** Mirrors {@code AccessibleTableCellInfo}. */
    class AccessibleTableCellInfo extends Structure {
        /** AccessibleContext of the cell itself — the handle a click ultimately acts on. */
        public long accessibleContext;
        public int index;
        public int row;
        public int column;
        public int rowExtent;
        public int columnExtent;
        /** {@code jboolean}, one byte. */
        public byte isSelected;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("accessibleContext", "index", "row", "column", "rowExtent",
                    "columnExtent", "isSelected");
        }
    }

    /** Mirrors {@code AccessibleTextInfo}. */
    class AccessibleTextInfo extends Structure {
        public int charCount;
        public int caretIndex;
        public int indexAtPoint;

        @Override
        protected List<String> getFieldOrder() {
            return List.of("charCount", "caretIndex", "indexAtPoint");
        }
    }

    /** Mirrors {@code AccessibleActionInfo}. */
    class AccessibleActionInfo extends Structure {
        public char[] name = new char[SHORT_STRING_SIZE];

        @Override
        protected List<String> getFieldOrder() {
            return List.of("name");
        }
    }

    /** Mirrors {@code AccessibleActions} (up to 256 actions). */
    class AccessibleActions extends Structure {
        public int actionsCount;
        public AccessibleActionInfo[] actionInfo = (AccessibleActionInfo[]) new AccessibleActionInfo().toArray(256);

        @Override
        protected List<String> getFieldOrder() {
            return List.of("actionsCount", "actionInfo");
        }
    }

    /** Mirrors {@code AccessibleActionsToDo}. */
    class AccessibleActionsToDo extends Structure {
        public int actionsCount;
        public AccessibleActionInfo[] actions = (AccessibleActionInfo[]) new AccessibleActionInfo().toArray(MAX_ACTIONS_TO_DO);

        @Override
        protected List<String> getFieldOrder() {
            return List.of("actionsCount", "actions");
        }
    }
}
