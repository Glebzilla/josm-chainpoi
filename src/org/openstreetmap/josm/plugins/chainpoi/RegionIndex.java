package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.stream.JsonGenerator;

import org.openstreetmap.josm.data.Bounds;

/**
 * Границы регионов сети, выученные по уже загруженным точкам.
 *
 * <p>Сайты «К&Б» и «Ярче!» отдают данные только по регионам. Раньше каждая
 * проверка делала 30–80 запросов, даже если картограф работает в одном
 * городе. Здесь запоминаются границы регионов: дальше запрашиваются только
 * те, что пересекаются с текущим участком карты.
 */
final class RegionIndex {

    static final class Box {
        final double minLat;
        final double minLon;
        final double maxLat;
        final double maxLon;
        final long time;
        final boolean empty;

        Box(double minLat, double minLon, double maxLat, double maxLon, long time) {
            this.minLat = minLat;
            this.minLon = minLon;
            this.maxLat = maxLat;
            this.maxLon = maxLon;
            this.time = time;
            this.empty = false;
        }

        Box(long time) {
            this.minLat = 0;
            this.minLon = 0;
            this.maxLat = 0;
            this.maxLon = 0;
            this.time = time;
            this.empty = true;
        }

        boolean intersects(Bounds b) {
            return !empty
                    && maxLat >= b.getMinLat() && minLat <= b.getMaxLat()
                    && maxLon >= b.getMinLon() && minLon <= b.getMaxLon();
        }
    }

    private final Path file;
    private final Map<String, Box> boxes = new LinkedHashMap<>();

    RegionIndex(String name) {
        this.file = StoreCache.path(name);
        load();
    }

    boolean isEmpty() {
        return boxes.isEmpty();
    }

    /**
     * Какие регионы нужно запросить для области: неизвестные, пересекающиеся
     * и устаревшие. Если индекс пуст — все.
     */
    List<String> needed(Collection<String> ids, Bounds area, long ttlMs) {
        if (boxes.isEmpty()) {
            return new ArrayList<>(ids);
        }
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            Box box = boxes.get(id);
            if (box == null) {
                out.add(id);
            } else if (!box.empty && box.intersects(area) && now - box.time >= ttlMs) {
                out.add(id);
            }
        }
        return out;
    }

    /** Регионы, которые точно пересекаются с областью (для восстановления кэша). */
    List<String> intersecting(Collection<String> ids, Bounds area) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            Box box = boxes.get(id);
            if (box != null && box.intersects(area)) {
                out.add(id);
            }
        }
        return out;
    }

    boolean intersects(String id, Bounds area) {
        Box box = boxes.get(id);
        return box != null && box.intersects(area);
    }

    boolean anyIntersects(Bounds area) {
        for (Box box : boxes.values()) {
            if (box.intersects(area)) {
                return true;
            }
        }
        return false;
    }

    /** Обновляет границы региона по полученным точкам. */
    void update(String id, List<Store> stores, long time) {
        if (stores.isEmpty()) {
            boxes.put(id, new Box(time));
            return;
        }
        double minLat = 90;
        double minLon = 180;
        double maxLat = -90;
        double maxLon = -180;
        for (Store s : stores) {
            minLat = Math.min(minLat, s.lat);
            maxLat = Math.max(maxLat, s.lat);
            minLon = Math.min(minLon, s.lon);
            maxLon = Math.max(maxLon, s.lon);
        }
        if (minLat > maxLat) {
            boxes.put(id, new Box(time));
            return;
        }
        // Небольшой запас, чтобы точки у границы не терялись.
        boxes.put(id, new Box(minLat - 0.1, minLon - 0.1, maxLat + 0.1, maxLon + 0.1, time));
    }

    void save() {
        try {
            Files.createDirectories(file.getParent());
            try (JsonGenerator g = Json.createGenerator(
                    Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
                g.writeStartObject().writeStartObject("regions");
                for (Map.Entry<String, Box> e : boxes.entrySet()) {
                    Box box = e.getValue();
                    g.writeStartObject(e.getKey())
                            .write("minLat", box.minLat)
                            .write("minLon", box.minLon)
                            .write("maxLat", box.maxLat)
                            .write("maxLon", box.maxLon)
                            .write("time", box.time)
                            .write("empty", box.empty)
                            .writeEnd();
                }
                g.writeEnd().writeEnd();
            }
        } catch (Exception ignored) {
            // индекс не критичен: без него просто запросим все регионы
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file);
             JsonReader r = Json.createReader(in)) {
            JsonObject root = r.readObject();
            JsonObject regions = root.getJsonObject("regions");
            if (regions == null) {
                return;
            }
            for (String id : regions.keySet()) {
                JsonObject o = regions.getJsonObject(id);
                boolean empty = o.getBoolean("empty", false);
                long time = o.getJsonNumber("time").longValue();
                if (empty) {
                    boxes.put(id, new Box(time));
                } else {
                    boxes.put(id, new Box(
                            o.getJsonNumber("minLat").doubleValue(),
                            o.getJsonNumber("minLon").doubleValue(),
                            o.getJsonNumber("maxLat").doubleValue(),
                            o.getJsonNumber("maxLon").doubleValue(),
                            time));
                }
            }
        } catch (Exception ignored) {
            boxes.clear();
        }
    }

}
