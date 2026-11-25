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

/** АЗС «Лукойл»: официальный локатор auto.lukoil.ru. */
public final class LukoilProvider implements ChainProvider {

    private static final String BASE = "https://auto.lukoil.ru/api/cartography/";
    private static final String CACHE_PREFIX = "chainpoi-lukoil";
    private static final String CACHE_NAME = CACHE_PREFIX + ".json";
    private static final long TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final String CHAIN_ID = "lukoil";
    private static final String CHAIN = "Лукойл";

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
        progress.accept("Лукойл: загрузка списка АЗС");
        JsonObject root = readObject(BASE + "GetSearchObjects?form=gasStation");
        JsonArray stations = root.getJsonArray("GasStations");
        JsonObject dependent = readObject(BASE + "GetCountryDependentSearchObjectData?form=gasStation&country=RU");
        JsonArray fuelRoot = readArray(BASE + "GetFuelClasses?country=RU");

        List<Integer> fuelIds = new ArrayList<>();
        for (JsonValue v : dependent.getJsonArray("FuelClasses")) {
            fuelIds.add(((JsonNumber) v).intValue());
        }
        Map<Integer, String> fuelNames = new HashMap<>();
        for (JsonValue v : fuelRoot) {
            JsonObject o = v.asJsonObject();
            fuelNames.put(o.getJsonNumber("FuelId").intValue(), o.getString("Name", ""));
        }
        Map<Integer, Integer> stationFuel = new HashMap<>();
        for (JsonValue v : dependent.getJsonArray("GasStations")) {
            JsonObject o = v.asJsonObject();
            JsonArray classes = o.getJsonArray("FuelClasses");
            int mask = classes == null || classes.isEmpty()
                    ? 0 : classes.getJsonNumber(0).intValue();
            stationFuel.put(o.getJsonNumber("GasStationId").intValue(), mask);
        }

        List<Store> out = new ArrayList<>();
        for (JsonValue v : stations) {
            JsonObject o = v.asJsonObject();
            int id = o.getJsonNumber("GasStationId").intValue();
            int status = o.getJsonNumber("GasStationStatus").intValue();
            if (status == 6 || status == 7) {
                continue;
            }
            double lat = o.getJsonNumber("Latitude").doubleValue();
            double lon = o.getJsonNumber("Longitude").doubleValue();
            if (lat == 0 || lon == 0) {
                continue;
            }
            Map<String, String> tags = tags();
            Integer mask = stationFuel.get(id);
            if (mask != null) {
                for (int bit = 0; bit < fuelIds.size(); bit++) {
                    if ((mask & (1 << bit)) != 0) {
                        String fuel = fuelNames.get(fuelIds.get(bit));
                        FuelTags.add(tags, fuel);
                    }
                }
            }
            String city = o.getString("City", "");
            String street = o.getString("Street", "");
            String address = city.isEmpty() ? street : street.isEmpty() ? city : city + ", " + street;
            out.add(new Store(CHAIN_ID, CHAIN, String.valueOf(id), address, lat, lon, "fuel", tags));
        }
        progress.accept("Лукойл: найдено " + out.size() + " АЗС");
        return out;
    }

    private static Map<String, String> tags() {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("amenity", "fuel");
        tags.put("name", CHAIN);
        tags.put("name:ru", CHAIN);
        tags.put("name:en", "Lukoil");
        tags.put("brand", CHAIN);
        tags.put("brand:ru", CHAIN);
        tags.put("brand:en", "Lukoil");
        tags.put("brand:wikidata", "Q329347");
        return tags;
    }

    private JsonObject readObject(String url) throws Exception {
        try (JsonReader reader = Json.createReader(new StringReader(body(url)))) {
            return reader.readObject();
        }
    }

    private JsonArray readArray(String url) throws Exception {
        try (JsonReader reader = Json.createReader(new StringReader(body(url)))) {
            return reader.readArray();
        }
    }

    private String body(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JOSM-chainpoi/0.2")
                .header("Accept", "application/json")
                .header("Referer", "https://auto.lukoil.ru/ru/ProductsAndServices/PetrolStations")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Лукойл: HTTP " + resp.statusCode());
        }
        return resp.body();
    }
}
