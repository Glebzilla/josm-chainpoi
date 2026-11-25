package org.openstreetmap.josm.plugins.chainpoi;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/** АЗС «Татнефть»: официальный локатор azs.tatneft.ru. */
public final class TatneftProvider implements ChainProvider {

    private static final String BASE = "https://azs.tatneft.ru/api/v2/azs/";
    private static final String CACHE_PREFIX = "chainpoi-tatneft";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "tatneft";
    private static final String CHAIN = "Татнефть";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Consumer<String> progress = s -> { };
    private volatile boolean allowDownload = true;

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

    public boolean hasFreshCache(Bounds bbox) {
        return AreaCache.hasFresh(CACHE_PREFIX, CACHE_NAME, bbox, TTL_MS);
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        return AreaCache.fetch(CACHE_PREFIX, CACHE_NAME, CHAIN_ID, CHAIN, "fuel",
                bbox, TTL_MS, () -> allowDownload ? downloadAll() : null, true);
    }

    private List<Store> downloadAll() throws Exception {
        progress.accept("Татнефть: загрузка списка АЗС");
        JsonObject root = read(BASE);
        JsonArray stations = root.getJsonArray("data");
        JsonObject fuelRoot = read(BASE + "fuel_types/");
        Map<Integer, String> fuels = new HashMap<>();
        JsonArray items = fuelRoot.getJsonObject("data").getJsonArray("items");
        for (JsonValue v : items) {
            JsonObject o = v.asJsonObject();
            fuels.put(o.getJsonNumber("id").intValue(), o.getString("title", ""));
        }
        List<Store> out = new ArrayList<>();
        for (JsonValue v : stations) {
            JsonObject o = v.asJsonObject();
            double lat = o.getJsonNumber("lat").doubleValue();
            double lon = o.getJsonNumber("lon").doubleValue();
            if (lat == 0 || lon == 0) {
                continue;
            }
            Map<String, String> tags = tags();
            JsonArray fuel = o.getJsonArray("fuel");
            if (fuel != null) {
                for (JsonValue f : fuel) {
                    JsonObject fo = f.asJsonObject();
                    String name = fuels.get(fo.getJsonNumber("fuel_type_id").intValue());
                    FuelTags.add(tags, name);
                }
            }
            String code = o.getJsonNumber("number") == null
                    ? String.valueOf(o.getJsonNumber("id").intValue())
                    : String.valueOf(o.getJsonNumber("number").intValue());
            if (o.getJsonNumber("number") != null) {
                tags.put("ref", code);
            }
            out.add(new Store(CHAIN_ID, CHAIN, code, o.getString("address", ""),
                    lat, lon, "fuel", tags));
        }
        progress.accept("Татнефть: найдено " + out.size() + " АЗС");
        return out;
    }

    private JsonObject read(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.2")
                .header("Accept", "application/json")
                .header("Referer", "https://azs.tatneft.ru/locator")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Татнефть: HTTP " + resp.statusCode());
        }
        try (JsonReader reader = Json.createReader(new StringReader(resp.body()))) {
            return reader.readObject();
        }
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "Tatneft");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "Tatneft");
        tags.put("brand:wikidata", "Q1616858");
        return tags;
    }
}
