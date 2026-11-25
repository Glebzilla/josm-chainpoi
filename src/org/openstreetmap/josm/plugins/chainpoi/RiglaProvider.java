package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
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
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;

/**
 * «Ригла»: официальный GraphQL-список аптек pharmdelivery.ru.
 *
 * <p>Один запрос отдаёт все аптеки группы (Ригла, Будь здоров, Живика,
 * партнёры Здравсити). Берём только точки с «Ригла» в названии, чтобы
 * не подписывать чужие аптеки брендом.
 */
public final class RiglaProvider implements ChainProvider {

    private static final String ENDPOINT = "https://www.pharmdelivery.ru/graphql";
    private static final String CACHE_PREFIX = "chainpoi-rigla";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "rigla";
    private static final String CHAIN = "Ригла";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private volatile Consumer<String> progress = s -> { };

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

    public boolean hasFreshCache(Bounds bbox) {
        return AreaCache.hasFresh(CACHE_PREFIX, CACHE_NAME, bbox, TTL_MS);
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        return AreaCache.fetch(CACHE_PREFIX, CACHE_NAME, CHAIN_ID, CHAIN, "pharmacy",
                bbox, TTL_MS, () -> {
                    progress.accept("Ригла: загрузка списка аптек");
                    return download();
                }, true);
    }

    private List<Store> download() throws Exception {
        String query = "query{pvzList(pageSize:1000000 currentPage:1){"
                + "page_info{total_pages current_page}"
                + "items{name entity_id partner_store_id address latitude longitude}}}";
        String body = Json.createObjectBuilder().add("query", query).build().toString();
        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = stream(resp);
             JsonReader reader = Json.createReader(in)) {
            JsonObject root = reader.readObject();
            JsonObject data = root.getJsonObject("data");
            if (data == null) {
                throw new IllegalStateException("Ригла: пустой ответ API");
            }
            JsonObject pvz = data.getJsonObject("pvzList");
            if (pvz == null) {
                throw new IllegalStateException("Ригла: нет pvzList в ответе");
            }
            JsonArray items = pvz.getJsonArray("items");
            List<Store> out = new ArrayList<>();
            if (items != null) {
                for (JsonValue v : items) {
                    Store s = toStore(v.asJsonObject());
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
            progress.accept("Ригла: найдено " + out.size() + " аптек");
            return out;
        }
    }

    private Store toStore(JsonObject o) {
        String name = o.getString("name", "");
        if (!name.toLowerCase(Locale.ROOT).contains("ригла")) {
            return null;
        }
        String code = o.getString("entity_id", "");
        if (code.isEmpty()) {
            return null;
        }
        double lat;
        double lon;
        try {
            lat = Double.parseDouble(o.getString("latitude", ""));
            lon = Double.parseDouble(o.getString("longitude", ""));
        } catch (RuntimeException ex) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "pharmacy");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("brand", CHAIN);
        tags.put("brand:wikidata", "Q4394431");
        tags.put("healthcare", "pharmacy");
        return new Store(CHAIN_ID, CHAIN, code, o.getString("address", ""),
                lat, lon, "pharmacy", tags);
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
