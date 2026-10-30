package org.julclang.difftest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The constructor tags of a case's records under their sealed parents (ADR-066 D3, G1-I4). An entry
 * {@code (record, parent, tag)} gives the tag of a record, by binary name such as {@code Probe$A}, as a variant
 * of the sealed interface {@code parent}. The generator knows each tag from the {@code permits} clause it printed;
 * nothing here derives a tag from reflection order, which Java leaves unspecified.
 * <p>
 * A table is valid on its own when, for each parent, the tags of its entries are distinct and cover
 * {@code 0..n-1}. {@link #validate} checks it against the compiled source before any run: every record that
 * implements a sealed interface has an entry for each such parent, and each parent's entries are exactly its
 * permitted subclasses. A record whose two parents give it different tags (#279) has no single Data encoding;
 * the table accepts it, and mapping such a value is a {@link HarnessException} ({@link #tagFor}).
 *
 * @param entries the entries, sorted by record, then parent
 */
public record TagTable(List<Entry> entries) {

    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::record).thenComparing(Entry::parent);

    /** No sealed values: standalone records only, tag 0. */
    public static final TagTable EMPTY = new TagTable(List.of());

    /** One tag: {@code record} is the variant with this tag of the sealed interface {@code parent}. */
    public record Entry(String record, String parent, int tag) {

        public Entry {
            if (record == null || record.isBlank() || parent == null || parent.isBlank()
                    || record.chars().anyMatch(Character::isWhitespace) || parent.chars().anyMatch(Character::isWhitespace)) {
                throw new HarnessException("A tag names a record and a parent, without spaces: " + record + " " + parent);
            }
            if (tag < 0) throw new HarnessException("A negative tag for " + record + " under " + parent + ": " + tag);
        }

        /** The text of a {@code tag:} line: {@code Probe$A Probe$S 0}. */
        @Override
        public String toString() {
            return record + " " + parent + " " + tag;
        }

        public static Entry parse(String text) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 3) throw new HarnessException("Expected tag: <record> <parent> <tag>: " + text);
            try {
                return new Entry(parts[0], parts[1], Integer.parseInt(parts[2]));
            } catch (NumberFormatException e) {
                throw new HarnessException("Not a tag: " + text);
            }
        }
    }

    public TagTable {
        entries = entries.stream().sorted(ORDER).toList();
        var families = new TreeMap<String, List<Integer>>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (i > 0 && ORDER.compare(entries.get(i - 1), entry) == 0) {
                throw new HarnessException("Two tags for " + entry.record() + " under " + entry.parent());
            }
            families.computeIfAbsent(entry.parent(), p -> new ArrayList<>()).add(entry.tag());
        }
        families.forEach((parent, tags) -> {
            var sorted = new TreeSet<>(tags);
            if (sorted.size() != tags.size()) throw new HarnessException("Duplicate tags under " + parent + ": " + tags);
            if (sorted.last() != tags.size() - 1) {
                throw new HarnessException("The tags under " + parent + " must cover 0.." + (tags.size() - 1) + ": " + sorted);
            }
        });
    }

    public static TagTable of(Entry... entries) {
        return new TagTable(List.of(entries));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The tag of {@code record} under {@code parent}, if the table has one. */
    public OptionalInt tag(Class<?> record, Class<?> parent) {
        for (var entry : entries) {
            if (entry.record().equals(record.getName()) && entry.parent().equals(parent.getName())) {
                return OptionalInt.of(entry.tag());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * The tag a value of {@code record} has when it is mapped at a use site of static type {@code useSite}: the
     * entry for {@code useSite} if that is one of the record's sealed parents, else its only tag, and 0 for a
     * record without a sealed parent. A record whose sealed parents give it different tags has no single encoding
     * (#279), whatever the use site, so mapping it is a {@link HarnessException} (boundary validation, ADR-066 D3).
     */
    public int tagFor(Class<?> record, Class<?> useSite) {
        var parents = sealedParents(record);
        if (parents.isEmpty()) {
            if (entries.stream().anyMatch(e -> e.record().equals(record.getName()))) {
                throw new HarnessException("A tag for " + record.getName() + ", which implements no sealed interface");
            }
            return 0;
        }
        var tags = new TreeMap<String, Integer>();
        for (Class<?> parent : parents) {
            var tag = tag(record, parent);
            if (tag.isEmpty()) {
                throw new HarnessException("No tag for " + record.getName() + " under " + parent.getName()
                        + "; a case that maps sealed values carries its tag table (ADR-066 G1-I4)");
            }
            tags.put(parent.getName(), tag.getAsInt());
        }
        if (new HashSet<>(tags.values()).size() > 1) {
            throw new HarnessException("No single Data encoding for " + record.getName() + ": its sealed parents give it"
                    + " different tags " + tags + " (#279)");
        }
        return tags.getOrDefault(useSite.getName(), tags.firstEntry().getValue());
    }

    /**
     * Source validation (ADR-066 D3): checks this table against the compiled program whose top-level class is
     * {@code top}. Every record and sealed interface nested in {@code top}, and every one an entry names, is
     * checked: each entry names a record that directly implements its sealed parent; every record that
     * implements a sealed interface has an entry for each such parent; and each parent's entries are exactly its
     * permitted subclasses, so its family has as many tags as the source has variants.
     */
    public void validate(Class<?> top) {
        var records = new LinkedHashSet<Class<?>>();
        var sealed = new LinkedHashSet<Class<?>>();
        var pending = new ArrayDeque<Class<?>>(List.of(top));
        while (!pending.isEmpty()) {
            Class<?> type = pending.pop();
            if (type.isRecord()) records.add(type);
            if (type.isInterface() && type.isSealed()) sealed.add(type);
            pending.addAll(Arrays.asList(type.getDeclaredClasses()));
        }
        for (var entry : entries) {
            Class<?> record = load(top, entry.record());
            Class<?> parent = load(top, entry.parent());
            if (!parent.isInterface() || !parent.isSealed()) {
                throw new HarnessException("tag: " + entry + ": " + entry.parent() + " is not a sealed interface");
            }
            if (!record.isRecord() || !Arrays.asList(record.getInterfaces()).contains(parent)) {
                throw new HarnessException("tag: " + entry + ": " + entry.record() + " is not a record that implements "
                        + entry.parent());
            }
            records.add(record);
            sealed.add(parent);
        }
        for (Class<?> record : records) {
            for (Class<?> parent : sealedParents(record)) {
                if (tag(record, parent).isEmpty()) {
                    throw new HarnessException("No tag for " + record.getName() + " under " + parent.getName());
                }
                sealed.add(parent);
            }
        }
        for (Class<?> parent : sealed) {
            Set<String> permitted = new TreeSet<>();
            for (Class<?> variant : parent.getPermittedSubclasses()) {
                if (!variant.isRecord()) {
                    throw new HarnessException(parent.getName() + " permits " + variant.getName() + ", which is not a record");
                }
                permitted.add(variant.getName());
            }
            Set<String> tagged = entries.stream().filter(e -> e.parent().equals(parent.getName()))
                    .map(Entry::record).collect(Collectors.toCollection(TreeSet::new));
            if (!tagged.equals(permitted)) {
                throw new HarnessException("The tags under " + parent.getName() + " name " + tagged + ", its permits clause "
                        + permitted);
            }
        }
    }

    /** The sealed interfaces that {@code record} directly implements. */
    static List<Class<?>> sealedParents(Class<?> record) {
        return Arrays.stream(record.getInterfaces()).filter(Class::isSealed).toList();
    }

    private static Class<?> load(Class<?> top, String name) {
        try {
            return Class.forName(name, false, top.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new HarnessException("tag: no class " + name + " in the program", e);
        }
    }

    /** The table as {@code tag:} lines' values, in order. */
    public List<String> lines() {
        return entries.stream().map(Entry::toString).toList();
    }
}
