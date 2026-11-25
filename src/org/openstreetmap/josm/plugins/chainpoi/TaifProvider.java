package org.openstreetmap.josm.plugins.chainpoi;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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

/** АЗС «ТАИФ-НК»: карта taifazs.ru. */
public final class TaifProvider implements ChainProvider {

    private static final String URL = "https://www.taifazs.ru/tools/get-fuels.php";
    private static final String CACHE_PREFIX = "chainpoi-taif";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "taif";
    private static final String CHAIN = "ТАИФ-НК";

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
        progress.accept("ТАИФ-НК: загрузка списка АЗС");
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.2")
                .header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", "https://www.taifazs.ru/map/map-gs/")
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofString("", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ТАИФ-НК: HTTP " + resp.statusCode());
        }
        List<Store> out = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(resp.body()))) {
            JsonArray array = reader.readArray();
            for (JsonValue v : array) {
                JsonObject o = v.asJsonObject();
                JsonArray coords = o.getJsonArray("fuelCoords");
                if (coords == null || coords.size() < 2) {
                    continue;
                }
                double lat;
                double lon;
                try {
                    lat = Double.parseDouble(coords.getString(0));
                    lon = Double.parseDouble(coords.getString(1));
                } catch (RuntimeException ex) {
                    continue;
                }
                Map<String, String> tags = tags();
                JsonValue fuelValue = o.get("fuelType");
                if (fuelValue instanceof JsonArray) {
                    for (JsonValue f : (JsonArray) fuelValue) {
                        if (f instanceof jakarta.json.JsonString) {
                            FuelTags.add(tags, ((jakarta.json.JsonString) f).getString());
                        }
                    }
                } else if (fuelValue instanceof jakarta.json.JsonString) {
                    FuelTags.add(tags, ((jakarta.json.JsonString) fuelValue).getString());
                }
                out.add(new Store(CHAIN_ID, CHAIN, o.getString("fuelShortcode", ""),
                        o.getString("fuelAdress", ""), lat, lon, "fuel", tags));
            }
        }
        progress.accept("ТАИФ-НК: найдено " + out.size() + " АЗС");
        return out;
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "TAIF-NK");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "TAIF-NK");
        tags.put("brand:wikidata", "Q112960401");
        return tags;
    }
}
