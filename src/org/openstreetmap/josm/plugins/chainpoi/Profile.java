package org.openstreetmap.josm.plugins.chainpoi;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Правила одной сети: как опознать её объект в OSM, что сверять и что создавать.
 */
public final class Profile {

    /** Идентификатор сети, совпадает с {@link Store#chainId}. */
    public final String id;
    /** Название для интерфейса. */
    public final String name;
    /** Ключи тегов в порядке сравнения и показа. */
    public final List<String> keys;
    /** Похож ли объект OSM на магазин этой сети. */
    public final Predicate<OsmPrimitive> matcher;
    /** Теги для новой точки. */
    public final Function<Store, Map<String, String>> createTags;
    /** Дополнительные предупреждения (может быть {@code null}). */
    public final BiFunction<Store, List<FieldDiff>, List<String>> warnings;

    public Profile(String id, String name, List<String> keys,
                   Predicate<OsmPrimitive> matcher,
                   Function<Store, Map<String, String>> createTags,
                   BiFunction<Store, List<FieldDiff>, List<String>> warnings) {
        this.id = id;
        this.name = name;
        this.keys = keys;
        this.matcher = matcher;
        this.createTags = createTags;
        this.warnings = warnings;
    }

    public boolean matches(OsmPrimitive p) {
        return matcher.test(p);
    }

    public Map<String, String> createTags(Store store) {
        return createTags.apply(store);
    }

    public List<String> warnings(Store store, List<FieldDiff> diffs) {
        return warnings == null ? List.of() : warnings.apply(store, diffs);
    }
}
