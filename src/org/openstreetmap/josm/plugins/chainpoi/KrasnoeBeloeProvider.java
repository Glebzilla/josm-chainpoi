package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * «Красное&Белое»: адресный каталог krasnoeibeloe.ru.
 *
 * <p>API отдаёт магазины только по региону целиком, запроса по bbox нет.
 * Поэтому один раз скачиваем все регионы, кладём в кэш и дальше фильтруем
 * по видимой области локально. Скачивание — только после подтверждения.
 */
public final class KrasnoeBeloeProvider implements ChainProvider {

    private static final String SITE = "https://krasnoeibeloe.ru";
    private static final String CACHE_PREFIX = "chainpoi-kb";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final String REGION_INDEX = "chainpoi-kb-regions.json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "krasnoe-beloe";
    private static final String CHAIN = "К&Б";
    private static final String FULL_NAME = "Красное&Белое";

    /** Регионы из селектора сайта; запасной список, если HTML не разобрался. */
    private static final String[] REGION_IDS = {
        "876", "2835", "3278", "2240", "2224", "777", "783", "790", "2001", "2005",
        "800", "3259", "3195", "805", "3153", "2962", "813", "3461", "2585", "2832",
        "819", "833", "2193", "3104", "842", "861", "2082", "871", "3576", "880",
        "2747", "3204", "950", "1981", "2410", "973", "984", "1017", "1023", "1033",
        "3256", "2030", "2227", "2876", "1071", "3180", "2362", "2209", "2175",
        "1127", "1139", "4228", "2050", "3174", "2016", "1167", "1178", "2053",
        "1188", "3424", "1211", "1271", "2188", "1282", "1291", "1306", "1308",
        "1321", "1146", "1347", "3239", "1352", "1377", "1437", "1447", "1458"
    };

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile boolean allowDownload;
    private volatile Consumer<String> progress = s -> { };

    @Override
    public String id() {
        return CHAIN_ID;
    }

    @Override
    public String name() {
        return CHAIN;
    }

    public void setAllowDownload(boolean allow) {
        this.allowDownload = allow;
    }

    public void setProgress(Consumer<String> progress) {
        this.progress = progress == null ? s -> { } : progress;
    }

    /** Есть ли свежий кэш для этой области. */
    public boolean hasFreshCache(Bounds bbox) {
        if (StoreCache.isFresh(CACHE_NAME, TTL_MS)) {
            // Старый общий кэш: данных хватает на любую область.
            return true;
        }
        RegionIndex index = new RegionIndex(REGION_INDEX);
        if (index.isEmpty()) {
            return false;
        }
        for (String id : index.intersecting(List.of(REGION_IDS), padded(bbox))) {
            if (!StoreCache.isFresh(regionFile(id), TTL_MS)) {
                return false;
            }
        }
        // Пересекающихся регионов нет — в этом районе сети не бывает.
        return true;
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        if (StoreCache.isFresh(CACHE_NAME, TTL_MS)) {
            return readLegacy(bbox);
        }
        RegionIndex index = new RegionIndex(REGION_INDEX);
        List<String> ids = List.of(REGION_IDS);
        Bounds area = padded(bbox);
        List<String> near = index.isEmpty()
                ? new ArrayList<>(ids)
                : index.intersecting(ids, area);
        List<String> needed = new ArrayList<>();
        for (String id : near) {
            if (!StoreCache.isFresh(regionFile(id), TTL_MS)) {
                needed.add(id);
            }
        }
        if (!needed.isEmpty() && allowDownload) {
            downloadRegions(needed, index, area);
            // Первый полный проход строит индекс, но на диск кладём только
            // регионы рядом с проверяемой областью.
            near = index.intersecting(ids, area);
        }
        return readRegions(near, bbox);
    }

    private void downloadRegions(List<String> ids, RegionIndex index, Bounds area) throws Exception {
        int i = 0;
        int total = 0;
        for (String id : ids) {
            i++;
            progress.accept("К&Б: регион " + i + " из " + ids.size());
            JsonArray shops = null;
            for (int attempt = 0; attempt < 2 && shops == null; attempt++) {
                try {
                    shops = getJsonArray(SITE + "/api/regions/" + id + "/shops/");
                } catch (Exception ex) {
                    if (attempt == 1) {
                        break;
                    }
                }
            }
            if (shops == null) {
                continue;
            }
            List<Store> region = new ArrayList<>();
            for (JsonValue v : shops) {
                Store s = toStore(v.asJsonObject());
                if (s != null) {
                    region.add(s);
                }
            }
            index.update(id, region, System.currentTimeMillis());
            if (index.intersects(id, area)) {
                StoreCache.write(regionFile(id), region);
            }
            total += region.size();
        }
        index.save();
        progress.accept("К&Б: загружено " + total + " магазинов");
    }

    private List<Store> readLegacy(Bounds bbox) {
        List<Store> all = StoreCache.read(CACHE_NAME, CHAIN_ID, CHAIN, "store");
        if (all == null) {
            return List.of();
        }
        Map<String, Store> byCode = new LinkedHashMap<>();
        for (Store s : all) {
            if (bbox.contains(new LatLon(s.lat, s.lon))) {
                byCode.putIfAbsent(s.code, s);
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private List<Store> readRegions(List<String> ids, Bounds bbox) {
        Map<String, Store> byCode = new LinkedHashMap<>();
        for (String id : ids) {
            List<Store> region = StoreCache.read(regionFile(id), CHAIN_ID, CHAIN, "store");
            if (region == null) {
                continue;
            }
            for (Store s : region) {
                if (bbox.contains(new LatLon(s.lat, s.lon))) {
                    byCode.putIfAbsent(s.code, s);
                }
            }
        }
        return new ArrayList<>(byCode.values());
    }

    private static String regionFile(String id) {
        return CACHE_PREFIX + "-r" + id + ".json";
    }

    private static Bounds padded(Bounds b) {
        return new Bounds(b.getMinLat() - 0.1, b.getMinLon() - 0.1,
                b.getMaxLat() + 0.1, b.getMaxLon() + 0.1);
    }

    private Store toStore(JsonObject o) {
        String code = firstNonEmpty(o, "shopNum", "num");
        if (code.isEmpty()) {
            return null;
        }
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(o.getString("lat", ""));
            lon = Double.parseDouble(o.getString("lng", ""));
        } catch (RuntimeException ex) {
            return null;
        }
        if (lat == 0 || lon == 0) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("shop", "alcohol");
        tags.put("name", FULL_NAME);
        tags.put("name:ru", FULL_NAME);
        tags.put("brand", FULL_NAME);
        tags.put("brand:wikidata", "Q24933790");
        String hours = openingHours(o.getString("time", ""));
        if (hours != null) {
            tags.put("opening_hours", hours);
        }
        return new Store(CHAIN_ID, CHAIN, code, o.getString("address", ""),
                lat, lon, "store", tags);
    }

    /** «пн-вс 9:00 - 22:05» → «Mo-Su 09:00-22:05». */
    static String openingHours(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        if (t.contains("круглосуточно")) {
            return "24/7";
        }
        Matcher m = Pattern.compile("([а-яё]+(?:\\s*-\\s*[а-яё]+)?)\\s*"
                + "(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})").matcher(t);
        if (!m.find()) {
            return null;
        }
        String days = days(m.group(1).replace(" ", ""));
        if (days == null) {
            return null;
        }
        return days + " " + pad(m.group(2)) + "-" + pad(m.group(3));
    }

    private static String days(String ru) {
        switch (ru) {
            case "пн-вс":
                return "Mo-Su";
            case "пн-пт":
                return "Mo-Fr";
            case "пн-сб":
                return "Mo-Sa";
            case "пн-чт":
                return "Mo-Th";
            case "пт-вс":
                return "Fr-Su";
            case "сб-вс":
                return "Sa-Su";
            case "пн":
                return "Mo";
            case "вт":
                return "Tu";
            case "ср":
                return "We";
            case "чт":
                return "Th";
            case "пт":
                return "Fr";
            case "сб":
                return "Sa";
            case "вс":
                return "Su";
            default:
                return null;
        }
    }

    private static String pad(String hhmm) {
        int colon = hhmm.indexOf(':');
        if (colon == 1) {
            return "0" + hhmm;
        }
        return hhmm;
    }

    private static String firstNonEmpty(JsonObject o, String... keys) {
        for (String k : keys) {
            String v = o.getString(k, "");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private JsonArray getJsonArray(String url) throws Exception {
        HttpRequest req = request(url).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = stream(resp);
             JsonReader reader = Json.createReader(in)) {
            return reader.readArray();
        }
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1");
    }

    private static InputStream stream(HttpResponse<InputStream> resp) throws Exception {
        String encoding = resp.headers().firstValue("Content-Encoding").orElse("");
        InputStream body = resp.body();
        if (encoding.toLowerCase(Locale.ROOT).contains("gzip")) {
            body = new GZIPInputStream(body);
        }
        return body;
    }

}
