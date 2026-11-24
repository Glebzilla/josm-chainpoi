package org.openstreetmap.josm.plugins.chainpoi;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * «Ярче!»: список супермаркетов по регионам на ярче.рф.
 *
 * <p>Страница {@code /shops/?region=...} отдаёт обычный HTML: город и адреса
 * с {@code data-coords}. Один запрос на регион, всего около 31 запроса.
 */
public final class YarcheProvider implements ChainProvider {

    private static final String BASE = "https://xn--e1avv4a.xn--p1ai/shops/?region=";
    private static final String CACHE_NAME = "chainpoi-yarche.json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "yarche";
    private static final String CHAIN = "Ярче!";

    private static final int[] REGIONS = {
            36, 55, 67, 45, 69, 61, 56, 48, 52, 37, 46, 65, 60, 40, 68, 38,
            43, 64, 70, 63, 50, 49, 54, 66, 58, 53, 51, 35, 47, 57, 59, 44
    };

    private static final Pattern LI = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL);
    private static final Pattern SPAN = Pattern.compile("<span[^>]*>(.*?)</span>", Pattern.DOTALL);
    private static final Pattern SHOP = Pattern.compile("<a[^>]*class=\"shop_address\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern DATA_ID = Pattern.compile("data-id=\"([^\"]+)\"");
    private static final Pattern DATA_COORDS = Pattern.compile("data-coords=\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Consumer<String> progress = s -> { };
    private volatile boolean allowDownload;

    @Override
    public String id() {
        return CHAIN_ID;
    }

    @Override
    public String name() {
        return CHAIN;
    }

    public void setProgress(Consumer<String> progress) {
        this.progress = progress == null ? s -> { } : progress;
    }

    public void setAllowDownload(boolean allow) {
        this.allowDownload = allow;
    }

    public boolean hasFreshCache() {
        return StoreCache.isFresh(CACHE_NAME, TTL_MS);
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        List<Store> all = StoreCache.read(CACHE_NAME, CHAIN_ID, CHAIN, "supermarket");
        if (all == null) {
            if (!allowDownload) {
                return List.of();
            }
            all = downloadAll();
            StoreCache.write(CACHE_NAME, all);
        } else if (allowDownload && !hasFreshCache()) {
            all = downloadAll();
            StoreCache.write(CACHE_NAME, all);
        }
        List<Store> out = new ArrayList<>();
        for (Store s : all) {
            if (bbox.contains(new LatLon(s.lat, s.lon))) {
                out.add(s);
            }
        }
        return out;
    }

    private List<Store> downloadAll() throws Exception {
        List<Store> out = new ArrayList<>();
        for (int i = 0; i < REGIONS.length; i++) {
            int region = REGIONS[i];
            progress.accept("Ярче!: регион " + (i + 1) + " из " + REGIONS.length);
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + region))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                continue;
            }
            out.addAll(parseRegion(resp.body()));
        }
        progress.accept("Ярче!: найдено " + out.size() + " магазинов");
        return out;
    }

    private List<Store> parseRegion(String html) {
        List<Store> out = new ArrayList<>();
        String city = "";
        Matcher li = LI.matcher(html);
        while (li.find()) {
            String block = li.group(1);
            Matcher span = SPAN.matcher(block);
            if (span.find()) {
                city = text(span.group(1));
                continue;
            }
            Matcher shop = SHOP.matcher(block);
            if (!shop.find()) {
                continue;
            }
            Matcher id = DATA_ID.matcher(shop.group(0));
            Matcher coords = DATA_COORDS.matcher(shop.group(0));
            if (!id.find() || !coords.find()) {
                continue;
            }
            String[] ll = coords.group(1).split(",");
            if (ll.length != 2) {
                continue;
            }
            double lat;
            double lon;
            try {
                lat = Double.parseDouble(ll[0].trim());
                lon = Double.parseDouble(ll[1].trim());
            } catch (NumberFormatException ex) {
                continue;
            }
            String addr = text(shop.group(1));
            // У одной точки на сайте вместо адреса продублированы координаты.
            if (addr.matches("^\\d+[.,]\\d+\\s*[, ]\\s*\\d+[.,]\\d+$")) {
                addr = "";
            }
            String fullAddress = addr.isEmpty() ? city : city + ", " + addr;
            Map<String, String> tags = new LinkedHashMap<>();
            tags.put("shop", "supermarket");
            tags.put("name", CHAIN);
            tags.put("name:ru", CHAIN);
            tags.put("name:en", "Yarche!");
            tags.put("brand", CHAIN);
            tags.put("brand:ru", CHAIN);
            tags.put("brand:en", "Yarche!");
            tags.put("brand:wikidata", "Q102254456");
            out.add(new Store(CHAIN_ID, CHAIN, id.group(1), fullAddress, lat, lon,
                    "supermarket", tags));
        }
        return out;
    }

    private static String text(String html) {
        String s = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&laquo;", "«").replace("&raquo;", "»")
                .replace("&mdash;", "—").replace("&ndash;", "–").trim();
    }
}
