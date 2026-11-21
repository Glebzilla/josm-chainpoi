package org.openstreetmap.josm.plugins.chainpoi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Результат сверки одного магазина с данными OSM.
 */
public final class Match {

    public enum Status {
        /** В OSM ничего похожего нет. */
        MISSING,
        /** Объект есть, но значения расходятся или тегов не хватает. */
        DIFF,
        /** Объект есть, всё совпадает. */
        OK,
        /** Объект есть, но размечен с ошибкой: на контуре здания или на входе. */
        BAD,
        /** В OSM магазин есть, а у сети его уже нет. */
        ORPHAN,
        /** Рядом объект другого формата сети (например, «М.Кос» вместо «М.Косметик»). */
        OTHER_FORMAT,
        /** Данные OSM не загружены. */
        NO_DATA
    }

    public final Status status;
    public final OsmPrimitive primitive;
    /** Расстояние до объекта в метрах, -1 если объекта нет. */
    public final double distance;
    /** Расхождения по тегам. */
    public final List<FieldDiff> diffs;
    /** Пояснения: почему объект считается проблемным. */
    public final List<String> warnings;

    private Match(Status status, OsmPrimitive primitive, double distance,
                  List<FieldDiff> diffs, List<String> warnings) {
        this.status = status;
        this.primitive = primitive;
        this.distance = distance;
        this.diffs = Collections.unmodifiableList(new ArrayList<>(diffs));
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
    }

    public static Match missing() {
        return new Match(Status.MISSING, null, -1, Collections.emptyList(), Collections.emptyList());
    }

    public static Match noData() {
        return new Match(Status.NO_DATA, null, -1, Collections.emptyList(), Collections.emptyList());
    }

    public static Match orphan(OsmPrimitive p, double distance, List<String> warnings) {
        return new Match(Status.ORPHAN, p, distance, Collections.emptyList(), warnings);
    }

    public static Match of(Status status, OsmPrimitive p, double distance,
                           List<FieldDiff> diffs, List<String> warnings) {
        return new Match(status, p, distance, diffs, warnings);
    }

    /** Короткий текст для таблицы. */
    public String text() {
        switch (status) {
            case MISSING:
                return "нет в OSM";
            case DIFF:
                return "расхождения: " + diffs.size();
            case OK:
                return "ок";
            case BAD:
                return "ошибка разметки";
            case ORPHAN:
                return "нет в данных сети";
            case OTHER_FORMAT:
                return "другой формат";
            default:
                return "нет данных";
        }
    }

    /** Есть ли что применять по этому объекту. */
    public boolean canApply() {
        return status == Status.MISSING
                || status == Status.ORPHAN
                || status == Status.DIFF
                || status == Status.BAD
                || (status == Status.OTHER_FORMAT && !diffs.isEmpty());
    }
}
