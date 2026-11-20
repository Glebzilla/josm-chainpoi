import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.plugins.chainpoi.MagnitProvider;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Печатает магазины сети в заданном прямоугольнике: TestBox minLat minLon maxLat maxLon. */
public class TestBox {
    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());
        double minLat = args.length > 0 ? Double.parseDouble(args[0]) : 45.363;
        double minLon = args.length > 1 ? Double.parseDouble(args[1]) : 40.704;
        double maxLat = args.length > 2 ? Double.parseDouble(args[2]) : 45.372;
        double maxLon = args.length > 3 ? Double.parseDouble(args[3]) : 40.715;
        Bounds b = new Bounds(minLat, minLon, maxLat, maxLon);
        System.out.println("box: " + b);
        int n = 0;
        for (Store s : new MagnitProvider().fetch(b)) {
            System.out.println(s.chainId + " | " + s.code + " | " + s.format + " | "
                    + s.lat + "," + s.lon + " | " + s.address);
            n++;
        }
        System.out.println("total: " + n);
    }
}
