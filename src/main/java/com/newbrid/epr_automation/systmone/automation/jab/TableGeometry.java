package com.newbrid.epr_automation.systmone.automation.jab;

/**
 * How big a table is, as the application itself reports it.
 *
 * <p>Not the same thing as counting the rows in the accessibility tree. A Swing {@code JTable}
 * exposes no row or cell children at all, so the tree says a table is empty however many rows it
 * has; and a virtualised grid exposes only what it is currently showing. This comes from the table
 * API, so it is the count in the model.</p>
 *
 * @param rows    rows in the model, header excluded or included exactly as the application counts
 *                them — which is its business, not ours to adjust
 * @param columns columns in the model
 */
public record TableGeometry(int rows, int columns) {

    public boolean isEmpty() {
        return rows <= 0 || columns <= 0;
    }
}
