package org.openstreetmap.josm.plugins.chainpoi;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;

/**
 * Сверяет магазины сети с объектами в загруженном слое.
 *
 * <p>Сравниваются не только ключи, но и значения: часы работы, название,
 * оператор. Магазин может быть точкой, полигоном или мультиполигоном.
 */
public final class Matcher {

    /** На каком расстоянии объект считается тем же магазином. */
    public static final double MAX_DISTANCE_M = 100.0;
    /** Ближе этого расстояния объект OSM считается найденным у сети. */
    public static final double ORPHAN_RADIUS_M = 300.0;
    /** Дальше этого расстояния появляется предупреждение. */
    public static final double FAR_WARNING_M = 50.0;

    private static final String CHAIN = "Магнит";

    /** Порядок сравнения и показа. */
    private static final String[] ORDER = {
            "shop", "name", "name:ru", "brand", "brand:wikidata", "operator", "opening_hours",
            "addr:street", "addr:housenumber"
    };

    private static final Set<String> STREET_WORDS = new HashSet<>(List.of(
            "улица", "ул", "проспект", "пр-т", "просп", "переулок", "пер",
            "шоссе", "ш", "бульвар", "б-р", "набережная", "наб", "площадь", "пл",
            "проезд", "тупик", "туп", "дом", "д"));

    private Matcher() {
    }

    public static Match check(Store store, DataSet ds) {
        if (ds == null) {
            return Match.noData();
        }
        OsmPrimitive best = null;
        double bestDistance = Double.MAX_VALUE;

        for (Node n : ds.getNodes()) {
            if (usable(n) && isSameChain(n, store)) {
                double d = distance(store.lat, store.lon, n.lat(), n.lon());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = n;
                }
            }
        }
        for (Way w : ds.getWays()) {
            if (usable(w) && w.hasKey("shop") && isSameChain(w, store)) {
                LatLon c = center(w);
                if (c != null) {
                    double d = distance(store.lat, store.lon, c.lat(), c.lon());
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = w;
                    }
                }
            }
        }
        for (Relation r : ds.getRelations()) {
            if (usable(r) && r.hasKey("shop") && isSameChain(r, store)) {
                LatLon c = center(r);
                if (c != null) {
                    double d = distance(store.lat, store.lon, c.lat(), c.lon());
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = r;
                    }
                }
            }
        }

        if (best == null || bestDistance > MAX_DISTANCE_M) {
            return Match.missing();
        }

        List<FieldDiff> diffs = compare(store, best, ds);
        List<String> warnings = new ArrayList<>();
        boolean bad = geometryWarnings(best, bestDistance, warnings);
        if (store.isCosmetic() && hasNameDiff(diffs)) {
            warnings.add("Переименование — по официальному ребрендингу сети (14.11.2025), "
                    + "а не по решению сообщества OSM; на панорамах вывеска может быть старой.");
        }

        Match.Status status;
        if (bad) {
            status = Match.Status.BAD;
        } else if (diffs.isEmpty()) {
            status = Match.Status.OK;
        } else {
            status = Match.Status.DIFF;
        }
        return Match.of(status, best, bestDistance, diffs, warnings);
    }

    /** Теги для новой точки: только данные сети. Адрес не дублируем. */
    public static Map<String, String> tagsForCreate(Store store) {
        return new LinkedHashMap<>(store.tags);
    }

    /** Адрес, который можно подсказать в панели (но не добавлять автоматически). */
    public static AddressTags addressHint(Store store, DataSet ds) {
        return AddressTags.derive(store, ds);
    }

    /**
     * Объекты сети в OSM, рядом с которыми у сети магазина уже нет.
     * Скорее всего закрылись, но расстояние может быть большим — решает человек.
     */
    public static List<Match> orphans(Collection<Store> stores, DataSet ds) {
        List<Match> out = new ArrayList<>();
        if (ds == null || stores == null) {
            return out;
        }
        for (OsmPrimitive p : chainPrimitives(ds)) {
            if (!p.hasKey("shop")) {
                continue;
            }
            String chain = chainOf(p);
            double d = nearestStore(stores, p, chain);
            if (d > ORPHAN_RADIUS_M) {
                List<String> warnings = new ArrayList<>();
                warnings.add("В OSM есть магазин сети, а у сети его в этом месте нет.");
                if (d < Double.MAX_VALUE) {
                    warnings.add("Ближайший магазин сети — в " + Math.round(d) + " м.");
                }
                out.add(Match.orphan(p, d, warnings));
            }
        }
        out.sort(Comparator.comparingDouble(m -> m.distance));
        return out;
    }

    /** Все объекты сети в слое (для второй половины сверки). */
    public static List<OsmPrimitive> chainPrimitives(DataSet ds) {
        List<OsmPrimitive> out = new ArrayList<>();
        if (ds == null) {
            return out;
        }
        for (Node n : ds.getNodes()) {
            if (usable(n) && isSameChain(n)) {
                out.add(n);
            }
        }
        for (Way w : ds.getWays()) {
            if (usable(w) && isSameChain(w)) {
                out.add(w);
            }
        }
        for (Relation r : ds.getRelations()) {
            if (usable(r) && isSameChain(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean usable(OsmPrimitive p) {
        return p != null && !p.isDeleted() && !p.isIncomplete();
    }

    private static double nearestStore(Collection<Store> stores, OsmPrimitive p, String chain) {
        LatLon c = center(p);
        if (c == null) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (Store s : stores) {
            if (chain != null && !chain.equals(s.chainId)) {
                continue;
            }
            double d = distance(s.lat, s.lon, c.lat(), c.lon());
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    private static List<FieldDiff> compare(Store store, OsmPrimitive p, DataSet ds) {
        Map<String, String> expected = tagsForCreate(store);
        AddressTags address = AddressTags.derive(store, ds);
        List<FieldDiff> diffs = new ArrayList<>();
        for (String key : ORDER) {
            if (key.startsWith("addr:")) {
                // Адрес не предлагаем добавлять: он обычно уже есть у здания.
                // Проверяем только то, что на объекте уже написано.
                String chain = address == null ? null
                        : ("addr:street".equals(key) ? address.street : address.housenumber);
                String osm = p.get(key);
                if (chain == null || osm == null) {
                    continue;
                }
                if (!sameValue(key, osm, chain)) {
                    diffs.add(new FieldDiff(key, osm, chain, FieldDiff.Kind.DIFFERENT));
                }
                continue;
            }
            // У М.Косметик не трогаем бренд и не навязываем новое имя.
            if ("brand".equals(key) && store.isCosmetic()) {
                continue;
            }
            if ("name:ru".equals(key) && !store.isCosmetic()) {
                continue;
            }
            String chain = expected.get(key);
            if (chain == null) {
                continue;
            }
            String osm = p.get(key);
            FieldDiff diff = null;
            if (osm == null) {
                diff = new FieldDiff(key, null, chain, FieldDiff.Kind.MISSING);
            } else if (!sameValue(key, osm, chain)) {
                diff = new FieldDiff(key, osm, chain, FieldDiff.Kind.DIFFERENT);
            }
            if (diff != null) {
                diffs.add(diff);
            }
        }
        return diffs;
    }

    private static boolean hasNameDiff(List<FieldDiff> diffs) {
        for (FieldDiff d : diffs) {
            if ("name".equals(d.key) || "name:ru".equals(d.key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверяет, не размечен ли магазин на контуре здания или на входе.
     *
     * @return {@code true}, если это ошибка разметки
     */
    private static boolean geometryWarnings(OsmPrimitive p, double d, List<String> warnings) {
        boolean bad = false;
        if (p instanceof Node) {
            Node n = (Node) p;
            if (n.hasKey("entrance") || n.hasKey("door")) {
                bad = true;
                warnings.add("Это вход или дверь, а не магазин: магазин размечают отдельной точкой.");
            }
            for (Way parent : n.getParentWays()) {
                if (parent.isDeleted()) {
                    continue;
                }
                if (parent.hasKey("building") || parent.hasKey("building:part")) {
                    bad = true;
                    warnings.add("Точка стоит на контуре здания — перенесите её внутрь.");
                    break;
                }
            }
        }
        if (d > FAR_WARNING_M) {
            warnings.add("Далеко от координат сети: " + Math.round(d) + " м — проверьте, тот ли это магазин.");
        }
        return bad;
    }

    /**
     * К какой сети относится объект OSM: {@code magnit}, {@code magnit-kosmetik}
     * или {@code null}. «Магнит Аптека» — отдельная сеть, её не трогаем.
     */
    public static String chainOf(OsmPrimitive p) {
        String brand = p.get("brand");
        String name = p.get("name");
        if (isKosmetik(brand) || isKosmetik(name)) {
            return "magnit-kosmetik";
        }
        if (isPlainMagnit(brand) || isPlainMagnit(name)) {
            return "magnit";
        }
        return null;
    }

    /** Относится ли объект к той же сети, что и магазин. */
    public static boolean isSameChain(OsmPrimitive p, Store store) {
        String chain = chainOf(p);
        return chain != null && chain.equals(store.chainId);
    }

    /** Любой объект сети из известных. */
    public static boolean isSameChain(OsmPrimitive p) {
        return chainOf(p) != null;
    }

    private static boolean isKosmetik(String value) {
        return value != null && basic(value).contains("косметик");
    }

    private static boolean isPlainMagnit(String value) {
        if (value == null) {
            return false;
        }
        String t = basic(value);
        if (t.contains("косметик") || t.contains("аптек")) {
            return false;
        }
        return t.startsWith("магнит");
    }

    private static LatLon center(OsmPrimitive p) {
        if (p instanceof Node) {
            return ((Node) p).getCoor();
        }
        try {
            return p.getBBox().getCenter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean sameValue(String key, String osm, String chain) {
        switch (key) {
            case "opening_hours":
                return normHours(osm).equals(normHours(chain));
            case "brand:wikidata":
                return osm.trim().equalsIgnoreCase(chain.trim());
            case "operator":
                return normOperator(osm).equals(normOperator(chain));
            case "addr:street":
                return sameStreet(osm, chain);
            case "addr:housenumber":
                return normHouse(osm).equals(normHouse(chain));
            default:
                return basic(osm).equals(basic(chain));
        }
    }

    static String basic(String s) {
        return s.trim().toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replace('«', '"').replace('»', '"')
                .replace('“', '"').replace('”', '"')
                .replace('–', '-').replace('—', '-')
                .replaceAll("\\s+", " ");
    }

    private static String normHours(String s) {
        return basic(s).replaceAll("\\s+", "");
    }

    private static String normOperator(String s) {
        String t = basic(s).replace("\"", "").replaceAll("\\s+", " ").trim();
        if (t.startsWith("ао ")) {
            t = t.substring(3).trim();
        }
        return t;
    }

    private static String normHouse(String s) {
        String t = basic(s).replaceAll("\\s+", "");
        t = t.replaceAll("^(дом|д)\\.?", "");
        return t;
    }

    private static boolean sameStreet(String a, String b) {
        return streetTokens(a).equals(streetTokens(b));
    }

    private static Set<String> streetTokens(String s) {
        Set<String> out = new HashSet<>();
        for (String w : basic(s).replaceAll("[.,()]", " ").split("\\s+")) {
            if (w.isEmpty() || STREET_WORDS.contains(w)) {
                continue;
            }
            out.add(w);
        }
        return out;
    }

    public static double distance(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
