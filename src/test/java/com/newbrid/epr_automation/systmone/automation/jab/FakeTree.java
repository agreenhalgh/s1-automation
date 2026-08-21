package com.newbrid.epr_automation.systmone.automation.jab;

import java.util.ArrayList;
import java.util.List;

/**
 * A scripted accessibility tree behind the {@link AccessBridge} interface, so the JAB backend and
 * the inspector can be tested without Windows, SystmOne or the Access Bridge itself.
 *
 * <p>Several roots stand for several top-level windows of one application — which is what a modal
 * dialog is, and what the real bridge reports the same way.</p>
 */
public final class FakeTree {

    private FakeTree() {}

    /** One node. Children are addressed by index, as JAB addresses them. */
    public static final class Node {
        private final String role;
        private final String name;
        private final String description;
        private final int x, y, w, h, indexInParent;
        private String states = "enabled,visible,showing";
        final List<Node> children = new ArrayList<>();

        private Node(String role, String name, String description,
                     int x, int y, int w, int h, int indexInParent) {
            this.role = role;
            this.name = name;
            this.description = description;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.indexInParent = indexInParent;
        }

        public Node add(Node child) {
            children.add(child);
            return this;
        }

        /** Mark this node as holding the keyboard focus, the way the real bridge reports it. */
        public Node focused() {
            this.states = "enabled,visible,showing,focused";
            return this;
        }

        /** Built but not on screen: an unselected tab, a card nobody flipped to. */
        public Node hidden() {
            this.states = "enabled";
            return this;
        }

        /** A node the bridge tells us nothing about, which must not be mistaken for hidden. */
        public Node statesUnknown() {
            this.states = "";
            return this;
        }

        /** childCount reflects the children actually attached, as the native struct does. */
        AccessibleInfo info() {
            return new AccessibleInfo(role, name, description, states, x, y, w, h,
                    children.size(), indexInParent);
        }
    }

    public static Node node(String role, String name, String description,
                            int x, int y, int w, int h, int indexInParent) {
        return new Node(role, name, description, x, y, w, h, indexInParent);
    }

    /** A node whose position on screen does not matter to the test. */
    public static Node node(String role, String name, int indexInParent) {
        return new Node(role, name, null, 0, 0, 100, 30, indexInParent);
    }

    public static AccessBridge bridge(Node... windows) {
        return new FakeBridge(windows);
    }

    /**
     * A bridge that tracks the lifetime of every reference it hands out, the way the target JVM
     * does: a handle is live from the moment it is returned until it is released.
     *
     * <p>It fails loudly on the three ways this goes wrong — reading through a released handle,
     * releasing one twice, and finishing with handles still live — because none of those show up
     * as a test failure otherwise. They show up as SystmOne slowing down over a day.</p>
     */
    public static Tracking tracking(Node... windows) {
        return new Tracking(new FakeBridge(windows));
    }

    /** Delegating bridge that accounts for every reference. */
    public static final class Tracking implements AccessBridge {
        private final AccessBridge delegate;
        private final java.util.Set<AcRef> live = new java.util.LinkedHashSet<>();
        private int opened;
        private int released;

        private Tracking(AccessBridge delegate) {
            this.delegate = delegate;
        }

        public int opened() { return opened; }
        public int released() { return released; }

        /** Handles the caller obtained and never gave back. */
        public java.util.Set<AcRef> leaked() { return live; }

        private AcRef open(AcRef ref) {
            if (ref != null) {
                live.add(ref);
                opened++;
            }
            return ref;
        }

        private void requireLive(AcRef ref, String what) {
            if (ref != null && !live.contains(ref)) {
                throw new IllegalStateException(what + " through a released handle " + ref
                        + " — the target JVM no longer holds that object");
            }
        }

        @Override public boolean isAvailable() { return delegate.isAvailable(); }

        @Override public List<AcRef> roots(String titleContains) {
            return delegate.roots(titleContains).stream().map(this::open).toList();
        }

        @Override public AccessibleInfo info(AcRef ref) {
            requireLive(ref, "read attributes");
            return delegate.info(ref);
        }

        @Override public AcRef child(AcRef ref, int index) {
            requireLive(ref, "read a child");
            return open(delegate.child(ref, index));
        }

        @Override public void release(AcRef ref) {
            if (ref == null) return;
            if (!live.remove(ref)) {
                throw new IllegalStateException("released handle " + ref + " twice");
            }
            released++;
        }

        @Override public boolean doAction(AcRef ref, String action) { requireLive(ref, "act"); return false; }
        @Override public boolean setText(AcRef ref, String text) { requireLive(ref, "set text"); return false; }
        @Override public String getText(AcRef ref) { requireLive(ref, "read text"); return null; }
    }

    /**
     * A bridge that counts how many nodes were decoded. Each {@code info} is a native call on a
     * real host, so the count is the cost of a traversal and the measure of what pruning saves.
     */
    public static Counting counting(Node... windows) {
        return new Counting(new FakeBridge(windows));
    }

    /** Delegating bridge that tallies decoded nodes. */
    public static final class Counting implements AccessBridge {
        private final AccessBridge delegate;
        private int reads;

        private Counting(AccessBridge delegate) {
            this.delegate = delegate;
        }

        public int reads() {
            return reads;
        }

        @Override public boolean isAvailable() { return delegate.isAvailable(); }
        @Override public List<AcRef> roots(String titleContains) { return delegate.roots(titleContains); }

        @Override public AccessibleInfo info(AcRef ref) {
            reads++;
            return delegate.info(ref);
        }

        @Override public AcRef child(AcRef ref, int index) { return delegate.child(ref, index); }
        @Override public boolean doAction(AcRef ref, String action) { return false; }
        @Override public boolean setText(AcRef ref, String text) { return false; }
        @Override public String getText(AcRef ref) { return null; }
        @Override public void release(AcRef ref) {}
    }

    private static final class FakeBridge implements AccessBridge {
        private final List<Node> handles = new ArrayList<>();
        private final List<Node> windows;

        FakeBridge(Node... windows) {
            this.windows = List.of(windows);
            this.windows.forEach(this::handleFor);
        }

        private long handleFor(Node node) {
            int existing = handles.indexOf(node);
            if (existing >= 0) return existing;
            handles.add(node);
            return handles.size() - 1;
        }

        @Override public boolean isAvailable() { return true; }

        @Override public List<AcRef> roots(String windowTitleContains) {
            return windows.stream().map(w -> new AcRef(1, handleFor(w))).toList();
        }

        @Override public AccessibleInfo info(AcRef ref) { return handles.get((int) ref.ac()).info(); }

        @Override public AcRef child(AcRef ref, int index) {
            List<Node> children = handles.get((int) ref.ac()).children;
            // Deliberately strict: a real out-of-range index reaches the native call, so a test
            // that asks for one should fail here rather than quietly return nothing.
            if (index < 0 || index >= children.size()) {
                throw new IllegalArgumentException("no child " + index + " of a node with "
                        + children.size() + " children — the caller should have range-checked");
            }
            return new AcRef(ref.vmId(), handleFor(children.get(index)));
        }

        @Override public boolean doAction(AcRef ref, String action) { return false; }
        @Override public boolean setText(AcRef ref, String text) { return false; }
        @Override public String getText(AcRef ref) { return null; }
        @Override public void release(AcRef ref) {}
    }
}
