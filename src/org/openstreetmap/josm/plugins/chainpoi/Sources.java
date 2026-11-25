package org.openstreetmap.josm.plugins.chainpoi;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Официальные источники данных сетей — для тега changeset.
 *
 * <p>Плагин берёт магазины с сайтов самих сетей, поэтому при применении
 * изменений в changeset добавляется {@code source:chainpoi} и дополняется
 * {@code source}. Это только атрибуция: на объекты OSM тег не ставится.
 */
public final class Sources {

    /** Тег changeset с источниками плагина. */
    public static final String TAG = "source:chainpoi";

    private Sources() {
    }

    /** Сайт сети по её идентификатору; {@code null}, если источник неизвестен. */
    public static String urlFor(String chainId) {
        if (chainId == null) {
            return null;
        }
        switch (chainId) {
            case "magnit":
            case "magnit-kosmetik":
                return "magnit.ru";
            case "pyaterochka":
                return "rabota5ka.ru";
            case "krasnoe-beloe":
                return "krasnoeibeloe.ru";
            case "ozon":
            case "ozon-box":
                return "ozon.ru";
            case "wildberries":
                return "wildberries.ru";
            case "yarche":
                return "ярче.рф";
            case "maria-ra":
                return "maria-ra.ru";
            case "lukoil":
                return "lukoil.ru";
            case "gazpromneft":
                return "gpnbonus.ru";
            case "tatneft":
                return "tatneft.ru";
            case "taif":
                return "taifazs.ru";
            case "topline":
                return "azs-topline.ru";
            case "neftmagistral":
                return "neftm.ru";
            case "rigla":
                return "rigla.ru";
            default:
                return null;
        }
    }

    /** Список сайтов через {@code "; "} без повторов. */
    public static String join(Collection<String> chainIds) {
        Set<String> urls = new LinkedHashSet<>();
        for (String id : chainIds) {
            String url = urlFor(id);
            if (url != null) {
                urls.add(url);
            }
        }
        return String.join("; ", urls);
    }
}
