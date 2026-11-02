package org.julclang.compiler.frontend.javac.subset;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;

import java.util.ArrayList;
import java.util.List;

/** Walks of a subtree that a rule of this package reads. */
final class SubsetWalk {

    private SubsetWalk() {
    }

    /** Every tree of {@code type} in {@code root}, {@code root} included, parents before their children. */
    static <T extends Tree> List<T> all(Tree root, Class<T> type) {
        var found = new ArrayList<T>();
        new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (type.isInstance(tree)) found.add(type.cast(tree));
                return super.scan(tree, unused);
            }
        }.scan(root, null);
        return found;
    }

    /** The trees directly under {@code node}, in source order. */
    static List<Tree> children(Tree node) {
        var children = new ArrayList<Tree>();
        node.accept(new TreeScanner<Void, Void>() {
            @Override
            public Void scan(Tree tree, Void unused) {
                if (tree != null) children.add(tree);
                return null;
            }
        }, null);
        return children;
    }
}
