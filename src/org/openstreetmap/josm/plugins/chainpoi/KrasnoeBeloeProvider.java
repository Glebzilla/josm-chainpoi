package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * «Красное&Белое»: адресный каталог krasnoeibeloe.ru.
 *
 * <p>API отдаёт магазины только по региону целиком, запроса по bbox нет.
 * Поэтому один раз скачиваем все регионы, кладём в кэш и дальше фильтруем
 * по видимой области локально. Скачивание — только после подтверждения.
 */
public final class KrasnoeBeloeProvider implements ChainProvider {

    private static final String SITE = "https://krasnoeibeloe.ru";
    private static final String CACHE_NAME = "chainpoi-kb.json";
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

    /** Есть ли свежий кэш (моложе 7 дней). */
    public boolean hasFreshCache() {
        Path cache = cacheFile();
        try {
            if (!Files.isRegularFile(cache)) {
                return false;
            }
            return System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < TTL_MS;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        List<Store> all = readCache();
        if (all == null) {
            if (!allowDownload) {
                return List.of();
            }
            all = downloadAll();
            progress.accept("К&Б: сохраняю кэш");
            writeCache(all);
            progress.accept("К&Б: кэш сохранён");
        } else if (allowDownload && !hasFreshCache()) {
            // кэш устарел, но человек согласился обновить
            all = downloadAll();
            progress.accept("К&Б: сохраняю кэш");
            writeCache(all);
            progress.accept("К&Б: кэш сохранён");
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
        Map<String, String> regions = new LinkedHashMap<>();
        try {
            String page = getText(SITE + "/address/");
            Matcher select = Pattern.compile("(?is)<select[^>]*name=\"region\".*?</select>")
                    .matcher(page);
            if (select.find()) {
                Matcher option = Pattern.compile(
                        "<option[^>]*value=\"(\\d+)\"[^>]*>([^<]+)</option>")
                        .matcher(select.group());
                while (option.find()) {
                    regions.put(option.group(1), option.group(2).trim());
                }
            }
        } catch (Exception ex) {
            // ниже возьмём встроенный список
        }
        if (regions.isEmpty()) {
            for (String id : REGION_IDS) {
                regions.put(id, id);
            }
        }

        Map<String, Store> byCode = new LinkedHashMap<>();
        int i = 0;
        for (Map.Entry<String, String> region : regions.entrySet()) {
            i++;
            progress.accept("К&Б: регион " + i + " из " + regions.size());
            JsonArray shops = null;
            for (int attempt = 0; attempt < 2 && shops == null; attempt++) {
                try {
                    shops = getJsonArray(SITE + "/api/regions/" + region.getKey() + "/shops/");
                } catch (Exception ex) {
                    if (attempt == 1) {
                        throw new IllegalStateException("регион " + region.getValue()
                                + ": " + ex.getMessage(), ex);
                    }
                }
            }
            for (JsonValue v : shops) {
                Store s = toStore(v.asJsonObject());
                if (s != null && !byCode.containsKey(s.code)) {
                    byCode.put(s.code, s);
                }
            }
        }
        progress.accept("К&Б: загружено " + byCode.size() + " магазинов");
        return new ArrayList<>(byCode.values());
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

    private String getText(String url) throws Exception {
        HttpRequest req = request(url).GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = stream(resp)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    // ------------------------------------------------------------------
    // кэш
    // ------------------------------------------------------------------

    private static Path cacheFile() {
        try {
            if (Config.getDirs() != null) {
                java.io.File dir = Config.getDirs().getUserDataDirectory(true);
                if (dir != null) {
                    return dir.toPath().resolve(CACHE_NAME);
                }
            }
        } catch (RuntimeException ex) {
            // в тестах каталоги JOSM могут быть не заданы
        }
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), CACHE_NAME);
    }

    private List<Store> readCache() {
        Path f = cacheFile();
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f);
             JsonReader r = Json.createReader(in)) {
            JsonObject root = r.readObject();
            List<Store> out = new ArrayList<>();
            for (JsonValue v : root.getJsonArray("shops")) {
                JsonObject o = v.asJsonObject();
                Map<String, String> tags = new LinkedHashMap<>();
                for (String key : o.getJsonObject("tags").keySet()) {
                    tags.put(key, o.getJsonObject("tags").getString(key));
                }
                out.add(new Store(CHAIN_ID, CHAIN, o.getString("code", ""),
                        o.getString("address", ""), o.getJsonNumber("lat").doubleValue(),
                        o.getJsonNumber("lon").doubleValue(), "store", tags));
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeCache(List<Store> shops) throws Exception {
        Path f = cacheFile();
        Files.createDirectories(f.getParent());
        try (jakarta.json.stream.JsonGenerator g = Json.createGenerator(
                Files.newBufferedWriter(f, StandardCharsets.UTF_8))) {
            g.writeStartObject().writeStartArray("shops");
            for (Store s : shops) {
                g.writeStartObject()
                        .write("code", s.code)
                        .write("address", s.address)
                        .write("lat", s.lat)
                        .write("lon", s.lon)
                        .writeStartObject("tags");
                for (Map.Entry<String, String> e : s.tags.entrySet()) {
                    g.write(e.getKey(), e.getValue());
                }
                g.writeEnd().writeEnd();
            }
            g.writeEnd().writeEnd();
        }
    }
}
