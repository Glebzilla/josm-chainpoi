package org.openstreetmap.josm.plugins.chainpoi;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.LatLon;

/**
 * Кэш «только рядом с проверяемой областью».
 *
 * <p>Раньше провайдеры сохраняли всю сеть целиком: картограф, работающий в
 * Краснодаре, получал на диск АЗС от Калининграда до Сахалина. Здесь кэш
 * режется на квадраты 1°×1°: сохраняются только те, что покрывают текущий
 * участок карты (плюс небольшой запас). Старые квадраты удаляются через месяц.
 *
 * <p>Полный ответ сервера остаётся в памяти на время сеанса JOSM. Поэтому
 * панорамирование в соседний район не вызывает повторную загрузку, но и на
 * диск не попадает ничего лишнего.
 */
final class AreaCache {

    /** Размер квадрата кэша в градусах. */
    private static final double TILE = 1.0;

    /** Запас вокруг видимой области, чтобы не качать заново при сдвиге карты. */
    private static final double PAD = 0.05;

    /** Сколько хранить квадраты, которые давно не запрашивались. */
    private static final long KEEP_MS = 30L * 24 * 60 * 60 * 1000;

    /** Слишком большие списки не держим в памяти целиком. */
    private static final int MEMORY_LIMIT = 30000;

    private static final Map<String, Dump> MEMORY = new HashMap<>();

    private AreaCache() {
    }

    /** Сбрасывает список, сохранённый в памяти (кнопка «Кэш»). */
    static void clearMemory() {
        MEMORY.clear();
    }

    interface Loader {
        /** Полный список сети или {@code null}, если загрузка запрещена. */
        List<Store> load() throws Exception;
    }

    private static final class Dump {
        final long time;
        final List<Store> stores;

        Dump(long time, List<Store> stores) {
            this.time = time;
            this.stores = stores;
        }
    }

    /**
     * Возвращает точки внутри {@code bbox}, используя квадратный кэш.
     *
     * @param prefix   начало имени файлов, например {@code chainpoi-lukoil}
     * @param legacy   имя старого общего файла кэша или {@code null}
     * @param remember держать полный список в памяти до конца сеанса
     */
    static List<Store> fetch(String prefix, String legacy, String chainId, String chainName,
                             String format, Bounds bbox, long ttlMs, Loader loader,
                             boolean remember) throws Exception {
        long now = System.currentTimeMillis();
        Bounds area = padded(bbox);
        List<long[]> tiles = tiles(area);
        Map<String, Store> cached = new LinkedHashMap<>();
        boolean missing = false;

        for (long[] tile : tiles) {
            String name = tileName(prefix, tile);
            if (!StoreCache.isFresh(name, ttlMs)) {
                missing = true;
                continue;
            }
            List<Store> part = StoreCache.read(name, chainId, chainName, format);
            if (part == null) {
                missing = true;
            } else {
                addAll(cached, part);
            }
        }
        if (!missing) {
            return inside(cached.values(), bbox);
        }

        List<Store> dump = null;
        Dump memory = MEMORY.get(prefix);
        if (memory != null && now - memory.time < ttlMs) {
            dump = memory.stores;
        }
        if (dump == null && legacy != null && StoreCache.isFresh(legacy, ttlMs)) {
            // Миграция: старый общий кэш ещё свежий — используем его без сети.
            dump = StoreCache.read(legacy, chainId, chainName, format);
        }
        if (dump == null) {
            dump = loader.load();
            if (dump == null) {
                return inside(cached.values(), bbox);
            }
        }
        if (remember && dump.size() <= MEMORY_LIMIT) {
            MEMORY.put(prefix, new Dump(now, dump));
        }

        for (long[] tile : tiles) {
            String name = tileName(prefix, tile);
            if (!StoreCache.isFresh(name, ttlMs)) {
                StoreCache.write(name, inside(dump, tileBounds(tile)));
            }
        }
        prune(prefix);
        if (legacy != null && !StoreCache.isFresh(legacy, ttlMs)) {
            // Старый общий кэш больше не нужен: остались квадраты по областям.
            StoreCache.delete(legacy);
        }
        return inside(dump, bbox);
    }

    /** Все квадраты вокруг области свежие? */
    static boolean hasFresh(String prefix, String legacy, Bounds bbox, long ttlMs) {
        if (legacy != null && StoreCache.isFresh(legacy, ttlMs)) {
            return true;
        }
        for (long[] tile : tiles(padded(bbox))) {
            if (!StoreCache.isFresh(tileName(prefix, tile), ttlMs)) {
                return false;
            }
        }
        return true;
    }

    private static Bounds padded(Bounds b) {
        double minLat = Math.max(-90, b.getMinLat() - PAD);
        double maxLat = Math.min(90, b.getMaxLat() + PAD);
        double minLon = Math.max(-180, b.getMinLon() - PAD);
        double maxLon = Math.min(180, b.getMaxLon() + PAD);
        return new Bounds(minLat, minLon, maxLat, maxLon);
    }

    private static List<long[]> tiles(Bounds b) {
        int minLat = (int) Math.floor(b.getMinLat() / TILE);
        int maxLat = (int) Math.floor(b.getMaxLat() / TILE);
        int minLon = (int) Math.floor(b.getMinLon() / TILE);
        int maxLon = (int) Math.floor(b.getMaxLon() / TILE);
        List<long[]> out = new ArrayList<>();
        for (int lat = minLat; lat <= maxLat; lat++) {
            for (int lon = minLon; lon <= maxLon; lon++) {
                out.add(new long[] {lat, lon});
            }
        }
        return out;
    }

    private static Bounds tileBounds(long[] tile) {
        return new Bounds(tile[0] * TILE, tile[1] * TILE,
                (tile[0] + 1) * TILE, (tile[1] + 1) * TILE);
    }

    private static String tileName(String prefix, long[] tile) {
        return prefix + "-t" + tile[0] + "_" + tile[1] + ".json";
    }

    private static List<Store> inside(Collection<Store> stores, Bounds bbox) {
        Map<String, Store> byKey = new LinkedHashMap<>();
        for (Store s : stores) {
            if (bbox.contains(new LatLon(s.lat, s.lon))) {
                add(byKey, s);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static void addAll(Map<String, Store> out, Collection<Store> stores) {
        for (Store s : stores) {
            add(out, s);
        }
    }

    private static void add(Map<String, Store> out, Store s) {
        String key = s.code == null || s.code.isEmpty()
                ? String.format(java.util.Locale.ROOT, "%.7f,%.7f", s.lat, s.lon)
                : s.code;
        out.putIfAbsent(key, s);
    }

    private static void prune(String prefix) {
        Path dir = StoreCache.path(prefix + "-t0_0.json").getParent();
        if (dir == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, prefix + "-t*.json")) {
            for (Path f : files) {
                try {
                    if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                        Files.deleteIfExists(f);
                    }
                } catch (Exception ignored) {
                    // не мешаем проверке из-за одного файла
                }
            }
        } catch (Exception ignored) {
            // каталога нет — чистить нечего
        }
    }
}
