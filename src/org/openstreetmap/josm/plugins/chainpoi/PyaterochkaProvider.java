package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Магазины «Пятёрочки» из базы вакансий rabota5ka.ru.
 *
 * <p>Сайт 5ka.ru закрыт антиботом, а API вакансий открыт и отдаёт координаты
 * и адрес магазина, где идёт набор. Поэтому в выборку попадают только магазины
 * с открытыми вакансиями — это не полный список сети.
 */
public final class PyaterochkaProvider implements ChainProvider {

    private static final String ENDPOINT = "https://rabota5ka.ru/api/vacancy/hire-request";
    private static final String CHAIN = "Пятёрочка";
    private static final String CHAIN_ID = "pyaterochka";
    private static final int LIMIT = 50;
    /** Больше 20 страниц (1000 вакансий) на один запрос не берём. */
    private static final int MAX_PAGES = 20;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public String id() {
        return CHAIN_ID;
    }

    @Override
    public String name() {
        return CHAIN;
    }

    @Override
    public List<Store> fetch(Bounds bbox) throws Exception {
        Map<String, Store> byOrg = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            JsonObject json = get(bbox, page);
            JsonArray items = json.getJsonArray("items");
            if (items == null || items.isEmpty()) {
                break;
            }
            for (JsonValue v : items) {
                Store s = toStore(v.asJsonObject(), bbox);
                if (s != null && !byOrg.containsKey(s.code)) {
                    byOrg.put(s.code, s);
                }
            }
            int pages = json.getInt("totalPages", 0);
            if (page >= pages) {
                break;
            }
        }
        return new ArrayList<>(byOrg.values());
    }

    private JsonObject get(Bounds b, int page) throws Exception {
        String url = ENDPOINT
                + "?portals%5B0%5D=x5"
                + "&page=" + page
                + "&limit=" + LIMIT
                + "&radius=30000&warehouseRadius=100"
                + "&bottomLeftX=" + enc(b.getMinLon())
                + "&bottomLeftY=" + enc(b.getMinLat())
                + "&topRightX=" + enc(b.getMaxLon())
                + "&topRightY=" + enc(b.getMaxLat());

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.1")
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        String encoding = resp.headers().firstValue("Content-Encoding").orElse("");
        InputStream body = resp.body();
        if (encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            body = new GZIPInputStream(body);
        }
        try (InputStream in = body;
             JsonReader reader = Json.createReader(in)) {
            return reader.readObject();
        }
    }

    private static String enc(double v) {
        return URLEncoder.encode(String.format(java.util.Locale.ROOT, "%.6f", v),
                StandardCharsets.UTF_8);
    }

    private static Store toStore(JsonObject o, Bounds bbox) {
        if (!"stores".equals(o.getString("jobDirection", ""))) {
            return null;
        }
        JsonObject pos = o.getJsonObject("position");
        if (pos == null) {
            return null;
        }
        JsonArray c = pos.getJsonArray("coordinates");
        if (c == null || c.size() < 2) {
            return null;
        }
        double lon = c.getJsonNumber(0).doubleValue();
        double lat = c.getJsonNumber(1).doubleValue();
        if (!bbox.contains(new LatLon(lat, lon))) {
            return null;
        }
        String org = o.getString("orgItemId", "");
        if (org.isEmpty()) {
            return null;
        }

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("shop", "supermarket");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("brand", CHAIN);
        tags.put("brand:wikidata", "Q1768969");
        tags.put("brand:wikipedia", "ru:Пятёрочка");
        return new Store(CHAIN_ID, CHAIN, org, o.getString("address", ""),
                lat, lon, "store", tags);
    }
}
