package com.newbrid.epr_automation.systmone.automation.jab;

/**
 * Reads the elements either side of the one being matched, on demand.
 *
 * <p>A function rather than a list because siblings are only needed by the locators that ask for
 * them. Decoding every sibling of every node during a traversal would cost a native call each,
 * for a field most locators never set — so the walk hands over the means to look one up and the
 * matcher calls it only if {@code previousSibling} or {@code followingSibling} is present.</p>
 */
public interface SiblingLookup {

    /** Knows nothing: for a top-level window, or a caller that did not track the parent. */
    SiblingLookup NONE = offset -> null;

    /**
     * The sibling {@code offset} places from this element — {@code -1} for the one immediately
     * before it, {@code +1} for the one immediately after — or null if there is none.
     */
    AccessibleInfo at(int offset);
}
