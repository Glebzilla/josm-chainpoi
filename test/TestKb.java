import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.plugins.chainpoi.KrasnoeBeloeProvider;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Проверка загрузки К&Б: регионы, кэш, фильтр по bbox. */
public class TestKb {
    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());
        KrasnoeBeloeProvider p = new KrasnoeBeloeProvider();
        p.setAllowDownload(true);
        p.setProgress(System.out::println);
        long t0 = System.currentTimeMillis();
        java.util.List<Store> list = p.fetch(new Bounds(55.75, 49.05, 55.90, 49.30));
        System.out.println("kb stores in box: " + list.size()
                + " in " + (System.currentTimeMillis() - t0) + " ms");
        for (Store s : list.subList(0, Math.min(3, list.size()))) {
            System.out.println("  " + s.code + " " + s.lat + "," + s.lon + " " + s.tags);
        }
        System.out.println("fresh cache: " + p.hasFreshCache());
    }
}
