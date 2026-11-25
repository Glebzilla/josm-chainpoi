package org.openstreetmap.josm.plugins.chainpoi;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;

import org.openstreetmap.josm.spi.preferences.Config;

/** Небольшой общий кэш списка магазинов для провайдеров сетей. */
final class StoreCache {

    private StoreCache() {
    }

    static boolean isFresh(String name, long ttlMs) {
        Path f = file(name);
        try {
            return Files.isRegularFile(f)
                    && System.currentTimeMillis() - Files.getLastModifiedTime(f).toMillis() < ttlMs;
        } catch (Exception ex) {
            return false;
        }
    }

    static List<Store> read(String name, String chainId, String chainName, String format) {
        Path f = file(name);
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
                out.add(new Store(chainId, chainName, o.getString("code", ""),
                        o.getString("address", ""), o.getJsonNumber("lat").doubleValue(),
                        o.getJsonNumber("lon").doubleValue(), format, tags));
            }
            return out;
        } catch (Exception ex) {
            return null;
        }
    }

    static void write(String name, List<Store> shops) throws Exception {
        Path f = file(name);
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

    private static Path file(String name) {
        try {
            if (Config.getDirs() != null) {
                java.io.File dir = Config.getDirs().getUserDataDirectory(true);
                if (dir != null) {
                    return dir.toPath().resolve(name);
                }
            }
        } catch (RuntimeException ex) {
            // тесты без каталогов JOSM
        }
        return java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), name);
    }

    static Path path(String name) {
        return file(name);
    }

    static boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(name));
        } catch (Exception ex) {
            return false;
        }
    }

    /** Удаляет все файлы плагина: старые общие и квадратные кэши. */
    static int clearAll() {
        Path dir = file("chainpoi-x.json").getParent();
        if (dir == null) {
            return 0;
        }
        int removed = 0;
        try (java.nio.file.DirectoryStream<Path> files =
                     Files.newDirectoryStream(dir, "chainpoi-*.json")) {
            for (Path f : files) {
                try {
                    if (Files.deleteIfExists(f)) {
                        removed++;
                    }
                } catch (Exception ignored) {
                    // занятый файл пропускаем
                }
            }
        } catch (Exception ignored) {
            // каталога нет
        }
        return removed;
    }
}
