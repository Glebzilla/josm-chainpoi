import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.Preferences;
import org.openstreetmap.josm.plugins.chainpoi.PyaterochkaProvider;
import org.openstreetmap.josm.plugins.chainpoi.Store;
import org.openstreetmap.josm.spi.preferences.Config;

/** Проверка провайдера Пятёрочки на реальном ответе. */
public class TestPyat {
    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new Preferences());
        Bounds b = new Bounds(45.34, 40.68, 45.39, 40.74);
        int n = 0;
        for (Store s : new PyaterochkaProvider().fetch(b)) {
            System.out.println(s.code + " | " + s.address + " | " + s.lat + "," + s.lon);
            n++;
        }
        System.out.println("total: " + n);
    }
}
