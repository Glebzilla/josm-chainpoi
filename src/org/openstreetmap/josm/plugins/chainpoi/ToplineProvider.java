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

/**
 * АЗС «Топлайн»: карта сети azs-topline.ru.
 *
 * <p>Одна страница-API отдаёт все АЗС с адресами, координатами и видами
 * топлива. Небольшая сеть (Омск и восточное направление), удобна для
 * проверки сверки по адресу.
 */
public final class ToplineProvider implements ChainProvider {

    private static final String URL = "https://www.azs-topline.ru/wp-json/topline/v1/azs-map-data";
    private static final String CACHE_PREFIX = "chainpoi-topline";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "topline";
    private static final String CHAIN = "Топлайн";

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
        progress.accept("Топлайн: загрузка списка АЗС");
        HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.3")
                .header("Accept", "application/json")
                .header("Referer", "https://azs-topline.ru/toplivo/")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Топлайн: HTTP " + resp.statusCode());
        }
        List<Store> out = new ArrayList<>();
        try (JsonReader reader = Json.createReader(new StringReader(resp.body()))) {
            JsonObject root = reader.readObject();
            JsonArray stations = root.getJsonArray("stations");
            if (stations == null) {
                return out;
            }
            for (JsonValue v : stations) {
                JsonObject o = v.asJsonObject();
                JsonArray coords = o.getJsonArray("coordinates");
                if (coords == null || coords.size() < 2) {
                    continue;
                }
                double lat = coords.getJsonNumber(0).doubleValue();
                double lon = coords.getJsonNumber(1).doubleValue();
                if (lat == 0 || lon == 0) {
                    continue;
                }
                Map<String, String> tags = tags();
                JsonArray fuels = o.getJsonArray("fuels");
                if (fuels != null) {
                    for (JsonValue f : fuels) {
                        FuelTags.add(tags, f.asJsonObject().getString("type", ""));
                    }
                }
                String code = String.valueOf(o.getJsonNumber("id").intValue());
                tags.put("ref", code);
                out.add(new Store(CHAIN_ID, CHAIN, code, o.getString("address", ""),
                        lat, lon, "fuel", tags));
            }
        }
        progress.accept("Топлайн: найдено " + out.size() + " АЗС");
        return out;
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "Topline");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "Topline");
        return tags;
    }
}
