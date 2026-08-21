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
        private String states = "enabled,visible";
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
            this.states = "enabled,visible,focused";
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
