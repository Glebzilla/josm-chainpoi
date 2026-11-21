package org.openstreetmap.josm.plugins.chainpoi;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;

/**
 * Превращает отмеченные строки таблицы в команды JOSM.
 *
 * <p>Только создаёт команды: ничего не выполняет и не добавляет в историю
 * правок. Это позволяет проверять логику без запуска интерфейса.
 */
public final class Applier {

    /** Что получилось из отмеченных строк. */
    public static final class Result {
        public final List<Command> commands = new ArrayList<>();
        /** Объекты, которые затронуты: их стоит выделить после применения. */
        public final List<OsmPrimitive> touched = new ArrayList<>();
        public int created;
        public int updated;
        public int closed;
        public int skipped;

        public boolean isEmpty() {
            return commands.isEmpty();
        }

        void merge(Result other) {
            commands.addAll(other.commands);
            touched.addAll(other.touched);
            created += other.created;
            updated += other.updated;
            closed += other.closed;
            skipped += other.skipped;
        }
    }

    private Applier() {
    }

    /** Команды по всем отмеченным строкам. */
    public static Result build(DataSet ds, List<ReviewTableModel.Row> rows) {
        Result total = new Result();
        if (rows == null) {
            return total;
        }
        for (ReviewTableModel.Row row : rows) {
            if (row.selected) {
                total.merge(buildRow(ds, row));
            }
        }
        return total;
    }

    /** Команды по одной строке, независимо от галочки в таблице. */
    public static Result buildRow(DataSet ds, ReviewTableModel.Row row) {
        Result r = new Result();
        if (ds == null || row == null || row.match == null) {
            r.skipped++;
            return r;
        }
        Match m = row.match;
        switch (m.status) {
            case MISSING: {
                Node node = newNode(ds, row.store);
                r.commands.add(new AddCommand(ds, node));
                r.touched.add(node);
                r.created++;
                break;
            }
            case ORPHAN: {
                Command c = closeCommand(ds, m.primitive);
                if (c == null) {
                    r.skipped++;
                } else {
                    r.commands.add(c);
                    r.touched.add(m.primitive);
                    r.closed++;
                }
                break;
            }
            case DIFF:
            case BAD:
            case OTHER_FORMAT: {
                Command c = tagCommand(ds, m);
                if (c == null) {
                    r.skipped++;
                } else {
                    r.commands.add(c);
                    r.touched.add(m.primitive);
                    r.updated++;
                }
                break;
            }
            default:
                r.skipped++;
                break;
        }
        return r;
    }

    private static Node newNode(DataSet ds, Store store) {
        Node node = new Node(new LatLon(store.lat, store.lon));
        node.putAll(Matcher.tagsForCreate(store));
        return node;
    }

    /** Дополняет объект тегами, отмеченными в панели. */
    private static Command tagCommand(DataSet ds, Match m) {
        OsmPrimitive old = m.primitive;
        if (old == null || old.isDeleted() || old.isIncomplete()) {
            return null;
        }
        Map<String, String> change = new LinkedHashMap<>();
        for (FieldDiff diff : m.diffs) {
            if (diff.accepted) {
                change.put(diff.key, diff.chainValue);
            }
        }
        if (change.isEmpty()) {
            return null;
        }
        return new ChangePropertyCommand(ds, Collections.singleton(old), change);
    }

    /** Магазин закрылся: shop → disused:shop, плюс дата проверки. */
    private static Command closeCommand(DataSet ds, OsmPrimitive p) {
        if (p == null || p.isDeleted() || p.isIncomplete()) {
            return null;
        }
        String shop = p.get("shop");
        if (shop == null) {
            shop = "supermarket";
        }
        Map<String, String> change = new LinkedHashMap<>();
        change.put("shop", null);
        change.put("disused:shop", shop);
        change.put("check_date", LocalDate.now().toString());
        return new ChangePropertyCommand(ds, Collections.singleton(p), change);
    }
}
