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
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/** АЗС «Газпромнефть»: карта gpnbonus.ru. */
public final class GazpromneftProvider implements ChainProvider {

    private static final String URL = "https://gpnbonus.ru/api/stations/list";
    private static final String CACHE_PREFIX = "chainpoi-gazpromneft";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "gazpromneft";
    private static final String CHAIN = "Газпромнефть";

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
        progress.accept("Газпромнефть: загрузка списка АЗС");
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(90))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.2")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Referer", "https://gpnbonus.ru/map")
                .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Газпромнефть: HTTP " + resp.statusCode());
        }
        JsonObject root;
        try (JsonReader reader = Json.createReader(new StringReader(resp.body()))) {
            root = reader.readObject();
        }
        Map<String, String> oils = new HashMap<>();
        for (JsonValue v : root.getJsonArray("oilProducts")) {
            JsonObject o = v.asJsonObject();
            oils.put(String.valueOf(o.getJsonNumber("id").intValue()), o.getString("title", ""));
        }
        List<Store> out = new ArrayList<>();
        for (JsonValue v : root.getJsonArray("stations")) {
            JsonObject o = v.asJsonObject();
            double lat;
            double lon;
            try {
                lat = Double.parseDouble(o.getString("latitude", ""));
                lon = Double.parseDouble(o.getString("longitude", ""));
            } catch (RuntimeException ex) {
                continue;
            }
            if (lat == 0 || lon == 0) {
                continue;
            }
            Map<String, String> tags = tags();
            JsonValue oilsValue = o.get("oils");
            if (oilsValue instanceof JsonObject) {
                JsonObject oilMap = (JsonObject) oilsValue;
                for (String id : oilMap.keySet()) {
                    if (oilMap.getBoolean(id, false)) {
                        FuelTags.add(tags, oils.get(id));
                    }
                }
            } else if (oilsValue instanceof JsonArray) {
                for (JsonValue fuelValue : (JsonArray) oilsValue) {
                    if (fuelValue instanceof JsonNumber) {
                        FuelTags.add(tags, oils.get(String.valueOf(((JsonNumber) fuelValue).intValue())));
                    }
                }
            }
            if (o.getString("workMode", "").toLowerCase(java.util.Locale.ROOT).contains("круглосуточно")) {
                tags.put("opening_hours", "24/7");
            }
            String city = o.getString("city", "");
            String addr = o.getString("address", "");
            String address = city.isEmpty() ? addr : addr.isEmpty() ? city : city + ", " + addr;
            String code = o.getString("PNPONumber", "").trim();
            if (code.isEmpty()) {
                code = String.valueOf(o.getJsonNumber("GPNAZSID").intValue());
            }
            out.add(new Store(CHAIN_ID, CHAIN, code, address, lat, lon, "fuel", tags));
        }
        progress.accept("Газпромнефть: найдено " + out.size() + " АЗС");
        return out;
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "Gazprom Neft");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "Gazprom Neft");
        tags.put("brand:wikidata", "Q1461799");
        return tags;
    }
}
