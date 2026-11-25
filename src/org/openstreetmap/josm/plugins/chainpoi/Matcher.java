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
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
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

    /** Ключи, которые проверяем, только если адрес уже стоит на объекте. */
    private static final List<String> ADDR_KEYS = List.of("addr:street", "addr:housenumber");

    /** «АЗС №5», «АЗС 5», «АЗК №12» — номер станции, а не название. */
    private static final Pattern STATION_NUMBER_IN_NAME = Pattern.compile(
            "(?iu).*№\\s*\\d+.*|^(?:азс|азк)\\s*\\d+.*");

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
        Profile profile = Profiles.get(store.chainId);
        OsmPrimitive best = null;
        double bestDistance = Double.MAX_VALUE;
        boolean byAddress = false;

        // У сетей с проверенным адресом сначала пробуем адрес: он надёжнее
        // координат, если сайт ставит точки «на глаз».
        if (profile.matchByAddress) {
            OsmPrimitive addressMatch = findAddressMatch(store, ds, profile);
            if (addressMatch != null) {
                best = addressMatch;
                LatLon c = center(addressMatch);
                bestDistance = c != null && hasCoordinates(store)
                        ? distance(store.lat, store.lon, c.lat(), c.lon()) : 0;
                byAddress = true;
            }
        }

        if (!byAddress) {
            for (Node n : ds.getNodes()) {
                if (usable(n) && profile.matches(n)) {
                    double d = distance(store.lat, store.lon, n.lat(), n.lon());
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = n;
                    }
                }
            }
            for (Way w : ds.getWays()) {
                if (usable(w) && (w.hasKey("shop") || w.hasKey("amenity"))
                        && profile.matches(w)) {
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
                if (usable(r) && (r.hasKey("shop") || r.hasKey("amenity"))
                        && profile.matches(r)) {
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
        }

        if ((best == null || bestDistance > profile.maxDistance) && !byAddress) {
            OsmPrimitive other = findMKos(store, ds);
            if (other != null) {
                LatLon c = center(other);
                if (c != null) {
                    double d = distance(store.lat, store.lon, c.lat(), c.lon());
                    // «М.Кос» — другой формат, но часы у сети общие: сверяем только их.
                    List<FieldDiff> diffs = new ArrayList<>();
                    String chainHours = store.tags.get("opening_hours");
                    String osmHours = other.get("opening_hours");
                    if (chainHours != null && !chainHours.trim().isEmpty()
                            && !sameValue("opening_hours", osmHours, chainHours)) {
                        diffs.add(new FieldDiff("opening_hours", osmHours, chainHours,
                                osmHours == null ? FieldDiff.Kind.MISSING : FieldDiff.Kind.DIFFERENT));
                    }
                    return Match.of(Match.Status.OTHER_FORMAT, other, d, diffs,
                            List.of("Рядом «М.Кос» — бьюти-формат, а не М.Косметик; "
                                    + "здесь сверяем только часы работы."));
                }
            }
            return Match.missing();
        }

        List<FieldDiff> diffs = compare(store, best, ds, profile, byAddress);
        List<String> warnings = new ArrayList<>();
        boolean bad = geometryWarnings(best, byAddress ? 0 : bestDistance, warnings);
        if (byAddress) {
            warnings.add("Совпадение по адресу, а не по координатам: проверьте, тот ли это объект.");
        }
        warnings.addAll(profile.warnings(store, diffs));
        if ("fuel".equals(best.get("amenity"))) {
            String name = best.get("name");
            if (name != null && STATION_NUMBER_IN_NAME.matcher(basic(name)).matches()) {
                warnings.add("В name указан номер АЗС. По правилам OSM номер станции "
                        + "пишется в ref без «№» и слова «АЗС», а name — это название "
                        + "или бренд (например, «Топлайн»).");
            }
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
        return Profiles.get(store.chainId).createTags(store);
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
        return orphans(stores, ds, null);
    }

    /** То же, но только объекты внутри проверенной области. */
    public static List<Match> orphans(Collection<Store> stores, DataSet ds, Bounds area) {
        List<Match> out = new ArrayList<>();
        if (ds == null || stores == null) {
            return out;
        }
        java.util.Set<String> loaded = new java.util.HashSet<>();
        for (Store s : stores) {
            loaded.add(s.chainId);
        }
        for (OsmPrimitive p : chainPrimitives(ds)) {
            if (!p.hasKey("shop") || (area != null && !inArea(p, area))) {
                continue;
            }
            Profile profile = Profiles.find(p);
            if (profile == null || !profile.completeSource || !loaded.contains(profile.id)) {
                continue;
            }
            double d = nearestStore(stores, p, profile.id);
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
            if (usable(n) && Profiles.find(n) != null) {
                out.add(n);
            }
        }
        for (Way w : ds.getWays()) {
            if (usable(w) && Profiles.find(w) != null) {
                out.add(w);
            }
        }
        for (Relation r : ds.getRelations()) {
            if (usable(r) && Profiles.find(r) != null) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Объекты сетей с неполным источником (Пятёрочка), которые не с чем сверять.
     * Показываем их отдельно, чтобы человек мог посмотреть/панорамы, но
     * «закрылся» по ним не предлагаем.
     */
    public static List<Match> unreviewedOsm(DataSet ds, Collection<String> chains,
                                            Collection<OsmPrimitive> used) {
        return unreviewedOsm(ds, chains, used, null);
    }

    public static List<Match> unreviewedOsm(DataSet ds, Collection<String> chains,
                                            Collection<OsmPrimitive> used, Bounds area) {
        List<Match> out = new ArrayList<>();
        if (ds == null || chains == null) {
            return out;
        }
        for (OsmPrimitive p : chainPrimitives(ds)) {
            if (used != null && used.contains(p)) {
                continue;
            }
            if (area != null && !inArea(p, area)) {
                continue;
            }
            Profile profile = Profiles.find(p);
            if (profile == null || profile.completeSource || !chains.contains(profile.id)) {
                continue;
            }
            out.add(Match.noData(p, -1,
                    List.of("Источник сети неполный (вакансии): объект в OSM не с чем сверять.")));
        }
        return out;
    }

    /**
     * Объект сети с тем же адресом, что и у сети.
     *
     * <p>Нужен, когда координаты сети неточные или их нет. Сравниваются
     * улица (без типа и регистра) и номер дома; город отдельно не проверяем,
     * потому что сверка идёт внутри загруженной области.
     */
    private static OsmPrimitive findAddressMatch(Store store, DataSet ds, Profile profile) {
        AddressTags target = AddressTags.parseLoose(store.address);
        if (target == null || target.street == null || target.housenumber == null) {
            return null;
        }
        List<OsmPrimitive> found = new ArrayList<>();
        for (Node n : ds.getNodes()) {
            if (usable(n) && profile.matches(n) && sameAddress(n, target)) {
                found.add(n);
            }
        }
        for (Way w : ds.getWays()) {
            if (usable(w) && (w.hasKey("shop") || w.hasKey("amenity"))
                    && profile.matches(w) && sameAddress(w, target)) {
                found.add(w);
            }
        }
        for (Relation r : ds.getRelations()) {
            if (usable(r) && (r.hasKey("shop") || r.hasKey("amenity"))
                    && profile.matches(r) && sameAddress(r, target)) {
                found.add(r);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        if (!hasCoordinates(store)) {
            return found.get(0);
        }
        OsmPrimitive best = found.get(0);
        double bestDistance = Double.MAX_VALUE;
        for (OsmPrimitive p : found) {
            LatLon c = center(p);
            if (c == null) {
                continue;
            }
            double d = distance(store.lat, store.lon, c.lat(), c.lon());
            if (d < bestDistance) {
                bestDistance = d;
                best = p;
            }
        }
        return best;
    }

    private static boolean sameAddress(OsmPrimitive p, AddressTags target) {
        AddressTags address = AddressTags.ofPrimitive(p);
        if (address == null || address.street == null || address.housenumber == null) {
            return false;
        }
        return sameStreet(address.street, target.street)
                && normHouse(address.housenumber).equals(normHouse(target.housenumber));
    }

    private static boolean hasCoordinates(Store store) {
        return !Double.isNaN(store.lat) && !Double.isNaN(store.lon)
                && (store.lat != 0 || store.lon != 0);
    }

    /** Ближайший объект «М.Кос» в пределах общего радиуса поиска. */
    private static OsmPrimitive findMKos(Store store, DataSet ds) {
        OsmPrimitive best = null;
        double bestD = MAX_DISTANCE_M;
        for (Node n : ds.getNodes()) {
            if (!usable(n) || !Profiles.isMKos(n)) {
                continue;
            }
            double d = distance(store.lat, store.lon, n.lat(), n.lon());
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        for (Way w : ds.getWays()) {
            if (!usable(w) || !Profiles.isMKos(w)) {
                continue;
            }
            LatLon c = center(w);
            if (c == null) {
                continue;
            }
            double d = distance(store.lat, store.lon, c.lat(), c.lon());
            if (d < bestD) {
                bestD = d;
                best = w;
            }
        }
        return best;
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

    private static List<FieldDiff> compare(Store store, OsmPrimitive p, DataSet ds,
                                           Profile profile, boolean byAddress) {
        Map<String, String> expected = profile.createTags(store);
        AddressTags address = byAddress
                ? AddressTags.parseLoose(store.address)
                : AddressTags.derive(store, ds);
        List<FieldDiff> diffs = new ArrayList<>();
        for (String key : profile.keys) {
            String chain = expected.get(key);
            if (chain == null) {
                continue;
            }
            String osm = p.get(key);
            if ("shop".equals(key) && "MC".equals(store.format)
                    && isSmallShop(osm) && isSmallShop(chain)) {
                // «Моя цена» бывает и convenience, и supermarket — не навязываем.
                continue;
            }
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
        // Адрес не предлагаем добавлять: он обычно уже есть у здания.
        // Проверяем только то, что на объекте уже написано.
        for (String key : ADDR_KEYS) {
            String chain = address == null ? null
                    : ("addr:street".equals(key) ? address.street : address.housenumber);
            String osm = p.get(key);
            if (chain == null || osm == null) {
                continue;
            }
            if (!sameValue(key, osm, chain)) {
                diffs.add(new FieldDiff(key, osm, chain, FieldDiff.Kind.DIFFERENT));
            }
        }
        return diffs;
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

    /** Объект внутри проверенной области (по центру). */
    private static boolean inArea(OsmPrimitive p, Bounds area) {
        LatLon c = center(p);
        return c != null && area.contains(c);
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

    /** Мелкий формат: convenience и supermarket для «Моей цены» равнозначны. */
    private static boolean isSmallShop(String value) {
        String t = basic(value);
        return "convenience".equals(t) || "supermarket".equals(t);
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
        String t = basic(s).replaceAll("\\s+", "");
        // OSM часто пишет без дней недели: 09:00-22:05 ≡ Mo-Su 09:00-22:05.
        return t.startsWith("mo-su") ? t.substring(5) : t;
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
