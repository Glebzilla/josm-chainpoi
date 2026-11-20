import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.io.OsmReader;
import org.openstreetmap.josm.plugins.chainpoi.MagnitProvider;
import org.openstreetmap.josm.plugins.chainpoi.Match;
import org.openstreetmap.josm.plugins.chainpoi.Matcher;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Сверка на реальных данных OSM из API, как в плагине. */
public class TestReal {
    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());

        String bbox = "40.8344,45.4439,40.8605,45.4561";
        String url = "https://api.openstreetmap.org/api/0.6/map?bbox=" + bbox;
        Path tmp = Files.createTempFile("osm-real", ".osm");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "JOSM-chainpoi-test/0.1").GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        System.out.println("api status: " + resp.statusCode());
        try (InputStream in = resp.body()) {
            Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("bytes: " + Files.size(tmp));

        DataSet ds;
        try (InputStream in = Files.newInputStream(tmp)) {
            ds = OsmReader.parseDataSet(in, null);
        }
        System.out.println("nodes=" + ds.getNodes().size() + " ways=" + ds.getWays().size());

        Bounds b = new Bounds(45.4439, 40.8344, 45.4561, 40.8605);
        java.util.List<Store> stores = new MagnitProvider().fetch(b);
        System.out.println("stores=" + stores.size());
        for (Store s : stores) {
            System.out.println("store " + s.code + " chainName='" + s.chainName + "' lat=" + s.lat + " lon=" + s.lon);
        }

        for (Store s : stores) {
            Match m = Matcher.check(s, ds);
            System.out.println("MATCH: " + m.status + " | " + m.text() + " | d=" + Math.round(m.distance));
            OsmPrimitive p = m.primitive;
            if (p != null) {
                System.out.println("  primitive id=" + p.getUniqueId() + " type=" + p.getType()
                        + " tags=" + p.getKeys());
                LatLon c = p.getBBox().getCenter();
                System.out.println("  bboxCenter=" + c.lat() + "," + c.lon());
                System.out.println("  distFromStore=" + Math.round(Matcher.distance(s.lat, s.lon, c.lat(), c.lon())));
                if (p instanceof Node) {
                    LatLon nc = ((Node) p).getCoor();
                    System.out.println("  nodeCoor=" + nc.lat() + "," + nc.lon());
                    System.out.println("  distFromNode=" + Math.round(Matcher.distance(s.lat, s.lon, nc.lat(), nc.lon())));
                }
            }
        }

        // ближайшие узлы с "Магнит" в тегах
        System.out.println("--- near magnit nodes ---");
        java.util.List<Node> mag = new java.util.ArrayList<>();
        for (Node n : ds.getNodes()) {
            String name = n.get("name");
            String brand = n.get("brand");
            if ((name != null && name.contains("Магнит")) || (brand != null && brand.contains("Магнит"))) {
                mag.add(n);
            }
        }
        mag.sort((a, c) -> Double.compare(
                Matcher.distance(stores.get(0).lat, stores.get(0).lon, a.getCoor().lat(), a.getCoor().lon()),
                Matcher.distance(stores.get(0).lat, stores.get(0).lon, c.getCoor().lat(), c.getCoor().lon())));
        for (int i = 0; i < Math.min(8, mag.size()); i++) {
            Node n = mag.get(i);
            System.out.println("  node/" + n.getId() + " d=" + Math.round(Matcher.distance(stores.get(0).lat,
                    stores.get(0).lon, n.getCoor().lat(), n.getCoor().lon())) + " tags=" + n.getKeys());
        }
    }
}
