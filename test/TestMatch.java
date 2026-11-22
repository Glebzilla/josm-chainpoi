import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.chainpoi.FieldDiff;
import org.openstreetmap.josm.plugins.chainpoi.MagnitProvider;
import org.openstreetmap.josm.plugins.chainpoi.Match;
import org.openstreetmap.josm.plugins.chainpoi.Matcher;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Проверка сверки: теги, геометрия, полигоны, соседние сети, М.Косметик. */
public class TestMatch {

    private static Store store;
    private static Store cosmetic;

    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());
        store = new MagnitProvider().fetch(new Bounds(45.4400, 40.8400, 45.4600, 40.8600)).get(0);
        System.out.println("store: " + store.code + " " + store.formatLabel() + " " + store.address);

        report("empty", store, Matcher.check(store, new DataSet()));
        report("full node", store, nodeWith(store, store.tags, store.lat, store.lon));

        DataSet pyat = new DataSet();
        Node pyatNode = new Node(new LatLon(55.8226, 49.5142));
        pyatNode.put("name", "Пятёрочка");
        pyatNode.put("shop", "supermarket");
        pyat.addPrimitive(pyatNode);
        List<Match> unchecked = Matcher.unreviewedOsm(pyat, List.of("pyaterochka"),
                java.util.Set.of());
        System.out.println("unreviewed pyat: " + unchecked.size()
                + (unchecked.isEmpty() ? "" : " " + unchecked.get(0).status
                + " / " + unchecked.get(0).text()));

        Map<String, String> wrongHours = new java.util.LinkedHashMap<>(store.tags);
        wrongHours.put("opening_hours", "Mo-Su 09:00-21:00");
        report("wrong hours", store, nodeWith(store, wrongHours, store.lat, store.lon));

        Map<String, String> prefixed = new java.util.LinkedHashMap<>(store.tags);
        prefixed.put("name", "магазин Магнит");
        report("name with prefix", store, nodeWith(store, prefixed, store.lat, store.lon));

        Map<String, String> noOperator = new java.util.LinkedHashMap<>(store.tags);
        noOperator.remove("operator");
        report("no operator", store, nodeWith(store, noOperator, store.lat, store.lon));

        report("building polygon", store, polygon(store));
        report("multipolygon", store, multipolygon(store));
        report("node on building outline", store, nodeOnWall(store));
        report("entrance node", store, entranceNode(store));

        DataSet kosmetik = new DataSet();
        Node k = new Node(new LatLon(store.lat, store.lon));
        k.put("shop", "chemist");
        k.put("name", "Магнит Косметик");
        k.put("brand", "Магнит Косметик");
        kosmetik.addPrimitive(k);
        report("kosmetik object at magnit store", store, Matcher.check(store, kosmetik));

        DataSet pharmacy = new DataSet();
        Node p = new Node(new LatLon(store.lat, store.lon));
        p.put("amenity", "pharmacy");
        p.put("name", "Магнит Аптека");
        p.put("brand", "Магнит Аптека");
        pharmacy.addPrimitive(p);
        report("apteka", store, Matcher.check(store, pharmacy));

        DataSet far = new DataSet();
        Node f = new Node(new LatLon(store.lat + 0.01, store.lon + 0.01));
        f.putAll(store.tags);
        far.addPrimitive(f);
        report("far away", store, Matcher.check(store, far));

        checkCosmetic();
    }

    /** Отдельная сеть: DG в API и объекты «Магнит Косметик» в OSM. */
    private static void checkCosmetic() throws Exception {
        List<Store> wide = new MagnitProvider().fetch(new Bounds(45.30, 40.60, 45.42, 40.75));
        for (Store s : wide) {
            if (s.isCosmetic()) {
                cosmetic = s;
                break;
            }
        }
        System.out.println("--- М.Косметик ---");
        if (cosmetic == null) {
            System.out.println("в проверяемой области нет DG — пропуск");
            return;
        }
        System.out.println("store: " + cosmetic.code + " " + cosmetic.formatLabel()
                + " " + cosmetic.address + " lat=" + cosmetic.lat + " lon=" + cosmetic.lon);

        report("empty", cosmetic, Matcher.check(cosmetic, new DataSet()));

        Map<String, String> old = new java.util.LinkedHashMap<>(cosmetic.tags);
        old.put("name", "Магнит Косметик");
        old.remove("name:ru");
        report("old name", cosmetic, nodeWith(cosmetic, old, cosmetic.lat, cosmetic.lon));

        report("new name", cosmetic,
                nodeWith(cosmetic, cosmetic.tags, cosmetic.lat, cosmetic.lon));

        Map<String, String> cosmetics = new java.util.LinkedHashMap<>(cosmetic.tags);
        cosmetics.put("shop", "cosmetics");
        report("shop=cosmetics (это уже М.Кос)", cosmetic,
                nodeWith(cosmetic, cosmetics, cosmetic.lat, cosmetic.lon));

        Map<String, String> mkos = new java.util.LinkedHashMap<>(cosmetics);
        mkos.put("name", "М.Кос");
        mkos.put("opening_hours", "Mo-Su 09:00-21:00");
        report("М.Кос, часы отличаются", cosmetic,
                nodeWith(cosmetic, mkos, cosmetic.lat, cosmetic.lon));

        mkos.put("opening_hours", cosmetic.tags.get("opening_hours"));
        report("М.Кос, часы совпадают", cosmetic,
                nodeWith(cosmetic, mkos, cosmetic.lat, cosmetic.lon));

        DataSet other = new DataSet();
        Node n = new Node(new LatLon(cosmetic.lat, cosmetic.lon));
        n.putAll(store.tags);
        n.put("shop", "supermarket");
        other.addPrimitive(n);
        report("supermarket at kosmetik store", cosmetic, Matcher.check(cosmetic, other));
    }

    private static void report(String label, Store s, Match m) {
        System.out.println(label + ": " + m.status + " | " + m.text()
                + " | d=" + Math.round(m.distance));
        for (FieldDiff d : m.diffs) {
            System.out.println("   " + d.changeText() + "   [" + d.key + ", " + d.kind
                    + (d.accepted ? "" : ", НЕ отмечено") + "]");
        }
        for (String w : m.warnings) {
            System.out.println("   ! " + w);
        }
    }

    private static Match nodeWith(Store s, Map<String, String> tags, double lat, double lon) {
        DataSet ds = new DataSet();
        Node n = new Node(new LatLon(lat, lon));
        n.putAll(tags);
        ds.addPrimitive(n);
        return Matcher.check(s, ds);
    }

    /** Магазин размечен полигоном здания. */
    private static Match polygon(Store s) {
        DataSet ds = new DataSet();
        List<Node> nodes = square(ds, s.lat, s.lon, 20);
        Way w = new Way();
        for (Node n : nodes) {
            w.addNode(n);
        }
        w.putAll(s.tags);
        w.put("building", "yes");
        ds.addPrimitive(w);
        return Matcher.check(s, ds);
    }

    /** Магазин размечен мультиполигоном. */
    private static Match multipolygon(Store s) {
        DataSet ds = new DataSet();
        List<Node> nodes = square(ds, s.lat, s.lon, 20);
        Way outer = new Way();
        for (Node n : nodes) {
            outer.addNode(n);
        }
        outer.put("building", "yes");
        ds.addPrimitive(outer);

        Relation r = new Relation();
        r.put("type", "multipolygon");
        r.putAll(s.tags);
        r.addMember(new RelationMember("outer", outer));
        ds.addPrimitive(r);
        return Matcher.check(s, ds);
    }

    /** Магазин посажен на вершину контура здания. */
    private static Match nodeOnWall(Store s) {
        DataSet ds = new DataSet();
        List<Node> nodes = square(ds, s.lat, s.lon, 20);
        Way building = new Way();
        for (Node n : nodes) {
            building.addNode(n);
        }
        building.put("building", "yes");
        ds.addPrimitive(building);

        Node shop = nodes.get(0);
        safePut(shop, s.tags);
        return Matcher.check(s, ds);
    }

    /** Магазин совмещён с входом. */
    private static Match entranceNode(Store s) {
        DataSet ds = new DataSet();
        Node n = new Node(new LatLon(s.lat, s.lon));
        n.putAll(s.tags);
        n.put("entrance", "yes");
        ds.addPrimitive(n);
        return Matcher.check(s, ds);
    }

    /** Правка объекта, уже лежащего в слое, требует блокировки набора данных. */
    private static void safePut(Node n, Map<String, String> tags) {
        DataSet ds = n.getDataSet();
        if (ds == null) {
            n.putAll(tags);
            return;
        }
        ds.beginUpdate();
        try {
            n.putAll(tags);
        } finally {
            ds.endUpdate();
        }
    }

    /** Квадрат со стороной sizeMeter метров вокруг точки. */
    private static List<Node> square(DataSet ds, double lat, double lon, double sizeMeter) {
        double dLat = sizeMeter / 2 / 111320.0;
        double dLon = sizeMeter / 2 / (111320.0 * Math.cos(Math.toRadians(lat)));
        List<Node> nodes = new ArrayList<>();
        double[][] corners = {
                {lat - dLat, lon - dLon}, {lat - dLat, lon + dLon},
                {lat + dLat, lon + dLon}, {lat + dLat, lon - dLon}
        };
        for (double[] c : corners) {
            Node n = new Node(new LatLon(c[0], c[1]));
            ds.addPrimitive(n);
            nodes.add(n);
        }
        return nodes;
    }
}
