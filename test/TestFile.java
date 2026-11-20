import java.util.List;

import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.plugins.chainpoi.Match;
import org.openstreetmap.josm.plugins.chainpoi.Matcher;
import org.openstreetmap.josm.plugins.chainpoi.PoiFile;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Проверка импорта выгрузки из браузера. */
public class TestFile {

    public static void main(String[] args) {
        Config.setPreferencesInstance(new Preferences());

        String five = "{\"chain\":\"pyaterochka\",\"points\":["
                + "{\"lat\":45.3677,\"lon\":40.7093,\"address\":\"ул. Ленинградская, 20\",\"code\":\"77\","
                + "\"tags\":{\"shop\":\"supermarket\",\"name\":\"Пятёрочка\",\"brand\":\"Пятёрочка\","
                + "\"brand:wikidata\":\"Q1768969\",\"opening_hours\":\"Mo-Su 08:00-23:00\"}},"
                + "{\"lat\":45.3700,\"lon\":40.7100,\"address\":\"ул. Пушкина, 1\",\"code\":\"78\","
                + "\"tags\":{\"shop\":\"supermarket\",\"name\":\"Пятёрочка\",\"brand\":\"Пятёрочка\","
                + "\"brand:wikidata\":\"Q1768969\"}}]}";
        PoiFile file = PoiFile.parse(five);
        System.out.println("chain=" + file.chainId + " points=" + file.stores.size());
        for (Store s : file.stores) {
            System.out.println("  " + s.lat + "," + s.lon + " " + s.address + " " + s.tags);
        }

        Store store = file.stores.get(0);
        DataSet ds = new DataSet();
        Node n = new Node(new LatLon(store.lat, store.lon));
        n.putAll(store.tags);
        ds.addPrimitive(n);
        Match m = Matcher.check(store, ds);
        System.out.println("match same tags: " + m.status + " diffs=" + m.diffs.size());

        n.put("name", "Пятерочка");
        ds.beginUpdate();
        try {
            n.put("name", "Пятерочка");
        } finally {
            ds.endUpdate();
        }
        System.out.println("match name without ё: " + Matcher.check(store, ds).status + " (ё и е равнозначны)");

        DataSet osm = new DataSet();
        Node p = new Node(new LatLon(store.lat, store.lon));
        p.put("shop", "supermarket");
        p.put("brand", "Пятёрочка");
        osm.addPrimitive(p);
        System.out.println("osn with brand only: " + Matcher.check(store, osm).status);

        String ozon = "{\"chain\":\"ozon\",\"points\":[{\"lat\":1,\"lon\":2,"
                + "\"tags\":{\"shop\":\"outpost\",\"name\":\"Ozon\",\"brand\":\"Ozon\","
                + "\"brand:wikidata\":\"Q2365235\"}}]}";
        System.out.println("ozon chain=" + PoiFile.parse(ozon).chainId);

        try {
            PoiFile.parse("{\"chain\":\"nope\",\"points\":[]}");
            System.out.println("unknown chain: НЕ ошибка (плохо)");
        } catch (IllegalArgumentException e) {
            System.out.println("unknown chain: ошибка — ok");
        }
    }
}
