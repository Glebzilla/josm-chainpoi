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
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.stream.JsonParser;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Wildberries: справочник ПВЗ одним статическим JSON.
 *
 * <p>Файл весит ~75 МБ и содержит ~98 тыс. точек (включая зарубежные).
 * Качаем один раз после подтверждения, кэшируем и фильтруем по bbox.
 */
public final class WildberriesProvider implements ChainProvider {

    private static final String BASE = "https://static-basket-01.wb.ru/vol0/data/all-poo-fr-v";
    private static final String CACHE_NAME = "chainpoi-wb.json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "wildberries";
    private static final String CHAIN = "Wildberries";

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

    public boolean hasFreshCache() {
        Path cache = cacheFile();
        try {
            return Files.isRegularFile(cache)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < TTL_MS;
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
            all = downloadAndParse();
            progress.accept("WB: сохраняю кэш");
            writeCache(all);
        } else if (allowDownload && !hasFreshCache()) {
            all = downloadAndParse();
            progress.accept("WB: сохраняю кэш");
            writeCache(all);
        }
        List<Store> out = new ArrayList<>();
        for (Store s : all) {
            if (bbox.contains(new LatLon(s.lat, s.lon))) {
                out.add(s);
            }
        }
        return out;
    }

    private List<Store> downloadAndParse() throws Exception {
        int version = findVersion();
        if (version < 0) {
            throw new IllegalStateException("не найден файл справочника WB");
        }
        String url = BASE + version + ".json";
        progress.accept("WB: скачивание " + url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = stream(resp)) {
            progress.accept("WB: разбор справочника");
            List<Store> out = parse(in);
            progress.accept("WB: загружено " + out.size() + " пунктов выдачи");
            return out;
        }
    }

    /** Последняя существующая версия файла (v12 сейчас, дальше может стать v13). */
    private int findVersion() {
        for (int v = 30; v >= 7; v--) {
            if (exists(BASE + v + ".json")) {
                return v;
            }
        }
        return -1;
    }

    private boolean exists(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    private static List<Store> parse(InputStream in) throws Exception {
        List<Store> out = new ArrayList<>();
        try (JsonParser p = Json.createParser(in)) {
            while (p.hasNext()) {
                JsonParser.Event e = p.next();
                if (e == JsonParser.Event.START_ARRAY) {
                    while (p.hasNext()) {
                        JsonParser.Event x = p.next();
                        if (x == JsonParser.Event.END_ARRAY) {
                            return out;
                        }
                        if (x == JsonParser.Event.START_OBJECT) {
                            parseCountry(p, out);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void parseCountry(JsonParser p, List<Store> out) throws Exception {
        String country = "";
        while (p.hasNext()) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_OBJECT) {
                return;
            }
            if (e != JsonParser.Event.KEY_NAME) {
                continue;
            }
            String key = p.getString();
            JsonParser.Event v = p.next();
            if ("country".equals(key) && v == JsonParser.Event.VALUE_STRING) {
                country = p.getString();
            } else if ("items".equals(key) && v == JsonParser.Event.START_ARRAY) {
                while (p.hasNext()) {
                    JsonParser.Event x = p.next();
                    if (x == JsonParser.Event.END_ARRAY) {
                        break;
                    }
                    if (x == JsonParser.Event.START_OBJECT) {
                        Store s = parseItem(p, country);
                        if (s != null) {
                            out.add(s);
                        }
                    }
                }
            } else {
                skip(p, v);
            }
        }
    }

    private static Store parseItem(JsonParser p, String country) throws Exception {
        String address = "";
        double lat = 0;
        double lon = 0;
        long id = 0;
        int type = -1;
        while (p.hasNext()) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.END_OBJECT) {
                break;
            }
            if (e != JsonParser.Event.KEY_NAME) {
                continue;
            }
            String key = p.getString();
            JsonParser.Event v = p.next();
            switch (key) {
                case "id":
                    if (v == JsonParser.Event.VALUE_NUMBER) {
                        id = p.getLong();
                    }
                    break;
                case "address":
                    if (v == JsonParser.Event.VALUE_STRING) {
                        address = p.getString();
                    }
                    break;
                case "typePoint":
                    if (v == JsonParser.Event.VALUE_NUMBER) {
                        type = p.getInt();
                    }
                    break;
                case "timeTableV2":
                    if (v == JsonParser.Event.VALUE_STRING) {
                        p.getString();
                    }
                    break;
                case "coordinates":
                    if (v == JsonParser.Event.START_ARRAY) {
                        lat = nextDouble(p);
                        lon = nextDouble(p);
                        skip(p, JsonParser.Event.START_ARRAY);
                    }
                    break;
                default:
                    skip(p, v);
                    break;
            }
        }
        if (!"ru".equals(country) || id == 0 || lat == 0 || lon == 0) {
            return null;
        }
        String lower = address.toLowerCase(Locale.ROOT);
        // Постаматы/локеры пока пропускаем: в OSM это amenity=parcel_locker,
        // а не shop=outpost, и тип точки в файле не документирован.
        if (type == 14 || lower.contains("постамат")) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("shop", "outpost");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("brand", CHAIN);
        tags.put("brand:wikidata", "Q24933714");
        return new Store(CHAIN_ID, CHAIN, String.valueOf(id), address, lat, lon, "pvz", tags);
    }

    private static double nextDouble(JsonParser p) throws Exception {
        while (p.hasNext()) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.VALUE_NUMBER) {
                return p.getBigDecimal().doubleValue();
            }
            if (e == JsonParser.Event.END_ARRAY) {
                return 0;
            }
        }
        return 0;
    }

    /** «Mo-Su 10:00-21:00», если все дни одинаковые; иначе часы не добавляем. */
    private static String hoursFrom(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try (JsonReader r = Json.createReader(new java.io.StringReader(json))) {
            JsonArray list = r.readArray();
            if (list.isEmpty()) {
                return null;
            }
            JsonObject week = list.getJsonObject(0);
            String first = null;
            for (String day : new String[] {"monday", "tuesday", "wednesday", "thursday",
                    "friday", "saturday", "sunday"}) {
                JsonObject d = week.getJsonObject(day);
                if (d == null || d.getBoolean("is_day_off", false)) {
                    return null;
                }
                String open = d.getString("open", "");
                String close = d.getString("close", "");
                if (open.isEmpty() || close.isEmpty()) {
                    return null;
                }
                String value = open + "-" + close;
                if (first == null) {
                    first = value;
                } else if (!first.equals(value)) {
                    return null;
                }
            }
            return first == null ? null : "Mo-Su " + first;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void skip(JsonParser p, JsonParser.Event first) throws Exception {
        if (first != JsonParser.Event.START_OBJECT && first != JsonParser.Event.START_ARRAY) {
            return;
        }
        int depth = 1;
        while (depth > 0 && p.hasNext()) {
            JsonParser.Event e = p.next();
            if (e == JsonParser.Event.START_OBJECT || e == JsonParser.Event.START_ARRAY) {
                depth++;
            } else if (e == JsonParser.Event.END_OBJECT || e == JsonParser.Event.END_ARRAY) {
                depth--;
            }
        }
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
            // тесты без каталогов JOSM
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
                        o.getJsonNumber("lon").doubleValue(), "pvz", tags));
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
