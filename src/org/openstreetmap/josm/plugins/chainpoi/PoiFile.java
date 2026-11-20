package org.openstreetmap.josm.plugins.chainpoi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Чтение выгрузки из браузера.
 *
 * <p>Формат общий для всех сетей — его формирует букмарклет на сайте сети:
 * <pre>
 * {
 *   "chain": "pyaterochka",
 *   "points": [
 *     {"lat": 45.1, "lon": 38.9, "address": "…", "code": "123",
 *      "tags": {"shop": "supermarket", "name": "Пятёрочка", …}}
 *   ]
 * }
 * </pre>
 */
public final class PoiFile {

    public final String chainId;
    public final List<Store> stores;

    private PoiFile(String chainId, List<Store> stores) {
        this.chainId = chainId;
        this.stores = stores;
    }

    public static PoiFile read(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             JsonReader reader = Json.createReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return parse(reader.readObject());
        }
    }

    public static PoiFile parse(String json) {
        try (JsonReader reader = Json.createReader(new java.io.StringReader(json))) {
            return parse(reader.readObject());
        }
    }

    static PoiFile parse(JsonObject root) {
        String chain = root.getString("chain", "");
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("в файле нет поля chain");
        }
        Profile profile = Profiles.get(chain);
        if (profile == null || !profile.id.equals(chain)) {
            throw new IllegalArgumentException("неизвестная сеть: " + chain);
        }
        JsonArray points = root.getJsonArray("points");
        List<Store> out = new ArrayList<>();
        if (points != null) {
            for (JsonValue v : points) {
                Store s = toStore(v.asJsonObject(), profile);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return new PoiFile(chain, out);
    }

    private static Store toStore(JsonObject o, Profile profile) {
        Double lat = number(o, "lat", "latitude");
        Double lon = number(o, "lon", "lng", "longitude");
        if (lat == null || lon == null) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        JsonObject t = o.getJsonObject("tags");
        if (t != null) {
            for (String key : t.keySet()) {
                JsonValue value = t.get(key);
                if (value instanceof JsonString) {
                    tags.put(key, ((JsonString) value).getString());
                }
            }
        }
        if (tags.isEmpty()) {
            return null;
        }
        String code = o.getString("code", "");
        String address = o.getString("address", "");
        String format = o.getString("format", "");
        return new Store(profile.id, profile.name, code, address, lat, lon, format, tags);
    }

    private static Double number(JsonObject o, String... names) {
        for (String name : names) {
            if (o.containsKey(name) && !o.isNull(name)) {
                try {
                    return o.getJsonNumber(name).doubleValue();
                } catch (ClassCastException e) {
                    try {
                        return Double.parseDouble(o.getString(name));
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
