import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.chainpoi.Applier;
import org.openstreetmap.josm.plugins.chainpoi.FieldDiff;
import org.openstreetmap.josm.plugins.chainpoi.MagnitProvider;
import org.openstreetmap.josm.plugins.chainpoi.Match;
import org.openstreetmap.josm.plugins.chainpoi.Matcher;
import org.openstreetmap.josm.plugins.chainpoi.ReviewTableModel;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Проверка применения: создание, выборочные поля, закрытие, отключённые строки. */
public class TestApply {

    private static Store store;

    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());
        store = new MagnitProvider()
                .fetch(new Bounds(45.4400, 40.8400, 45.4600, 40.8600)).get(0);
        System.out.println("store: " + store.code + " " + store.formatLabel());

        checkMissing();
        checkPartialSelectedFields();
        checkOrphan();
        checkUnselected();
    }

    private static void checkMissing() {
        DataSet ds = new DataSet();
        ReviewTableModel.Row row = new ReviewTableModel.Row(store, Matcher.check(store, ds));
        System.out.println("[missing] status=" + row.match.status + " selected=" + row.selected);
        Applier.Result r = Applier.buildRow(ds, row);
        System.out.println("  created=" + r.created + " commands=" + r.commands.size()
                + " touched=" + r.touched.size());
        r.commands.get(0).executeCommand();
        Node n = ds.getNodes().iterator().next();
        System.out.println("  tags=" + n.getKeys());
        System.out.println("  has addr:street=" + n.hasKey("addr:street")
                + " (адрес не добавляем)");
        System.out.println("  match again=" + Matcher.check(store, ds).status);
    }

    private static void checkPartialSelectedFields() {
        DataSet ds = new DataSet();
        Map<String, String> tags = new LinkedHashMap<>(store.tags);
        tags.put("opening_hours", "Mo-Su 09:00-21:00");
        tags.remove("operator");
        Node n = new Node(new LatLon(store.lat, store.lon));
        n.putAll(tags);
        ds.addPrimitive(n);

        Match m = Matcher.check(store, ds);
        System.out.println("[diff] status=" + m.status + " diffs=" + m.diffs.size());
        for (FieldDiff d : m.diffs) {
            System.out.println("  " + d.key + ": " + d.changeText());
            if ("opening_hours".equals(d.key)) {
                d.accepted = false;   // часы не трогаем
            }
        }

        ReviewTableModel.Row row = new ReviewTableModel.Row(store, m);
        Applier.Result r = Applier.buildRow(ds, row);
        System.out.println("  updated=" + r.updated + " commands=" + r.commands.size());
        r.commands.get(0).executeCommand();
        Node after = ds.getNodes().iterator().next();
        System.out.println("  opening_hours=" + after.get("opening_hours") + " (должно остаться 09:00-21:00)");
        System.out.println("  operator=" + after.get("operator"));
        System.out.println("  match again=" + Matcher.check(store, ds).status);
    }

    private static void checkOrphan() {
        DataSet ds = new DataSet();
        Node n = new Node(new LatLon(store.lat, store.lon));
        n.putAll(store.tags);
        ds.addPrimitive(n);

        List<Match> orphans = Matcher.orphans(Collections.emptyList(), ds);
        System.out.println("[orphan] found=" + orphans.size()
                + (orphans.isEmpty() ? "" : " status=" + orphans.get(0).status));

        ReviewTableModel.Row row = new ReviewTableModel.Row(null, orphans.get(0));
        System.out.println("  selected by default=" + row.selected + " (должно быть false)");
        Applier.Result r = Applier.buildRow(ds, row);
        System.out.println("  closed=" + r.closed + " commands=" + r.commands.size());
        r.commands.get(0).executeCommand();
        Node after = ds.getNodes().iterator().next();
        System.out.println("  shop=" + after.get("shop") + " (должно быть null)");
        System.out.println("  disused:shop=" + after.get("disused:shop"));
        System.out.println("  check_date=" + after.get("check_date"));
    }

    private static void checkUnselected() {
        DataSet ds = new DataSet();
        ReviewTableModel.Row row = new ReviewTableModel.Row(store, Matcher.check(store, ds));
        row.selected = false;
        List<ReviewTableModel.Row> rows = new ArrayList<>();
        rows.add(row);
        Applier.Result r = Applier.build(ds, rows);
        System.out.println("[unselected] commands=" + r.commands.size()
                + " created=" + r.created + " skipped=" + r.skipped);
    }
}
