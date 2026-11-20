package org.openstreetmap.josm.plugins.chainpoi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Известные сети и их правила.
 *
 * <p>Чтобы добавить сеть, достаточно описать профиль: как опознавать её объекты,
 * какие теги сверять и с какими создавать новую точку.
 */
public final class Profiles {

    private static final String MAGNIT = "Магнит";
    private static final String KOSMETIK = "М.Косметик";

    private static final List<String> MAGNIT_KEYS = List.of(
            "shop", "name", "brand", "brand:wikidata", "operator", "opening_hours");
    private static final List<String> KOSMETIK_KEYS = List.of(
            "shop", "name", "name:ru", "operator", "opening_hours");

    private static final Profile MAGNIT_PROFILE = new Profile(
            "magnit", MAGNIT, MAGNIT_KEYS,
            Profiles::isPlainMagnit,
            store -> new LinkedHashMap<>(store.tags),
            null);

    private static final Profile KOSMETIK_PROFILE = new Profile(
            "magnit-kosmetik", KOSMETIK, KOSMETIK_KEYS,
            Profiles::isKosmetik,
            store -> new LinkedHashMap<>(store.tags),
            (store, diffs) -> {
                for (FieldDiff d : diffs) {
                    if ("name".equals(d.key) || "name:ru".equals(d.key)) {
                        return List.of("Переименование — по официальному ребрендингу сети (14.11.2025), "
                                + "а не по решению сообщества OSM; на панорамах вывеска может быть старой.");
                    }
                }
                return List.of();
            });

    private static final List<Profile> ALL = List.of(MAGNIT_PROFILE, KOSMETIK_PROFILE);

    private Profiles() {
    }

    public static List<Profile> all() {
        return ALL;
    }

    public static Profile get(String id) {
        for (Profile p : ALL) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return MAGNIT_PROFILE;
    }

    /** Профиль, к которому относится объект OSM, или {@code null}. */
    public static Profile find(OsmPrimitive p) {
        for (Profile profile : ALL) {
            if (profile.matches(p)) {
                return profile;
            }
        }
        return null;
    }

    /** Обычный Магнит: «Магнит», но не «Магнит Косметик» и не «Магнит Аптека». */
    static boolean isPlainMagnit(OsmPrimitive p) {
        return plainMagnit(p.get("brand")) || plainMagnit(p.get("name"));
    }

    /** М.Косметик: в названии или бренде есть «косметик». */
    static boolean isKosmetik(OsmPrimitive p) {
        return kosmetik(p.get("brand")) || kosmetik(p.get("name"));
    }

    private static boolean plainMagnit(String value) {
        if (value == null) {
            return false;
        }
        String t = basic(value);
        if (t.contains("косметик") || t.contains("аптек")) {
            return false;
        }
        return t.startsWith("магнит");
    }

    private static boolean kosmetik(String value) {
        return value != null && basic(value).contains("косметик");
    }

    private static String basic(String s) {
        return s.trim().toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('«', '"').replace('»', '"')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replaceAll("\\s+", " ");
    }
}
