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
    private static final String KB = "К&Б";

    private static final List<String> MAGNIT_KEYS = List.of(
            "shop", "name", "brand", "brand:wikidata", "operator", "opening_hours");
    private static final List<String> KOSMETIK_KEYS = List.of(
            "shop", "name", "name:ru", "operator", "opening_hours");
    private static final List<String> SHOP_KEYS = List.of(
            "shop", "name", "brand", "brand:wikidata", "operator", "opening_hours");
    private static final List<String> LOCKER_KEYS = List.of(
            "amenity", "name", "brand", "brand:wikidata", "operator");
    private static final List<String> KB_KEYS = List.of(
            "shop", "name", "name:ru", "brand", "brand:wikidata", "opening_hours");

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

    private static final List<Profile> ALL = List.of(
            MAGNIT_PROFILE,
            KOSMETIK_PROFILE,
            new Profile("krasnoe-beloe", KB, KB_KEYS,
                    Profiles::isKrasnoeBeloe,
                    store -> new LinkedHashMap<>(store.tags), null),
            new Profile("pyaterochka", "Пятёрочка", SHOP_KEYS,
                    p -> shopNamed(p, "пятерочка"),
                    store -> new LinkedHashMap<>(store.tags), null)
                    .incompleteSource()
                    .withRadius(250)
                    .withNote("Источник — вакансии: часов работы нет, координаты могут быть "
                            + "неточными. Проверьте магазин по панорамам."),
            new Profile("ozon", "Ozon", SHOP_KEYS,
                    p -> "outpost".equals(p.get("shop")) && named(p, "ozon", "озон"),
                    store -> new LinkedHashMap<>(store.tags), null),
            new Profile("ozon-box", "Ozon Box", LOCKER_KEYS,
                    p -> "parcel_locker".equals(p.get("amenity")) && named(p, "ozon", "озон"),
                    store -> new LinkedHashMap<>(store.tags), null),
            new Profile("wildberries", "Wildberries", SHOP_KEYS,
                    p -> shopNamed(p, "wildberries", "вайлдберриз"),
                    store -> new LinkedHashMap<>(store.tags), null));

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

    /** М.Косметик: в названии или бренде есть «косметик», но это не «М.Кос». */
    static boolean isKosmetik(OsmPrimitive p) {
        if (isMKos(p)) {
            return false;
        }
        return kosmetik(p.get("brand")) || kosmetik(p.get("name"));
    }

    /** «Красное&Белое»: brand/name, допускаем пробелы и разные амперсанды. */
    static boolean isKrasnoeBeloe(OsmPrimitive p) {
        return kbName(p.get("brand")) || kbName(p.get("name"));
    }

    private static boolean kbName(String value) {
        if (value == null) {
            return false;
        }
        String t = basic(value).replace(" ", "").replace("&", "");
        return t.contains("красноебелое");
    }

    /** «М.Кос» — бьюти-формат, не М.Косметик; такие объекты не трогаем. */
    public static boolean isMKos(OsmPrimitive p) {
        return isMKosName(p.get("name"));
    }

    static boolean isMKosName(String value) {
        if (value == null) {
            return false;
        }
        String t = basic(value).replace(".", "").replace(" ", "");
        return "мкос".equals(t);
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

    /** Магазин (shop) нужного бренда: по brand или name. */
    private static boolean shopNamed(OsmPrimitive p, String... names) {
        return named(p, names) && p.hasKey("shop");
    }

    /** Совпадает ли бренд или название с одним из вариантов. */
    private static boolean named(OsmPrimitive p, String... names) {
        String brand = p.get("brand");
        String name = p.get("name");
        for (String n : names) {
            if (contains(brand, n) || contains(name, n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String value, String needle) {
        return value != null && basic(value).contains(basic(needle));
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
