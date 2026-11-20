import java.util.List;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.plugins.chainpoi.MagnitProvider;
import org.openstreetmap.josm.plugins.chainpoi.Store;

/** Временная проверка провайдера без запуска JOSM. */
public class TestFetch {
    public static void main(String[] args) throws Exception {
        Bounds b = new Bounds(45.4400, 40.8400, 45.4600, 40.8600);
        List<Store> stores = new MagnitProvider().fetch(b);
        System.out.println("stores=" + stores.size());
        for (Store s : stores) {
            System.out.println(s.code + " | " + s.format + " | " + s.address + " | " + s.tags);
        }
    }
}
