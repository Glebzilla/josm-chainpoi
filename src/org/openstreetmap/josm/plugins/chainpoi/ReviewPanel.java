package org.openstreetmap.josm.plugins.chainpoi;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;

import org.openstreetmap.josm.actions.JosmAction;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Панель «Сетевые магазины»: запрос данных сети, сверка с OSM, разбор по одному.
 */
public class ReviewPanel extends ToggleDialog {

    private static final double MAX_AREA = 0.5;
    /** Масштаб при переходе к магазину: ~80 м по высоте, чтобы был виден дом, а не район. */
    private static final double POINT_SPAN_DEG = 0.0007;

    private static final Color RED = new Color(0xB0, 0x00, 0x20);
    private static final Color ORANGE = new Color(0xB3, 0x5C, 0x00);
    private static final Color GREEN = new Color(0x1B, 0x7A, 0x1B);
    private static final Color VIOLET = new Color(0x6A, 0x3D, 0x9A);
    private static final Color BLUE = new Color(0x1B, 0x5E, 0x9A);
    /** Колонка с кнопкой «Пан.» в каждой строке. */
    private static final int PANO_COLUMN = 5;

    /** Источники, которые опрашиваются при проверке области. */
    private static final List<ChainProvider> PROVIDERS = List.of(
            new MagnitProvider(), new PyaterochkaProvider());

    private final ReviewTableModel model = new ReviewTableModel();
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel("Область ещё не проверялась");
    private final JLabel hint = new JLabel(" ");
    private final JButton checkButton = new JButton("Проверить");
    private final JButton fileButton = new JButton("Импорт…");
    private final JButton applyButton = new JButton("Принять отмеченные");
    private final ReviewDetailPanel detail;
    private final CheckAction checkAction;

    /** Сети, для которых есть прямой источник данных. */
    private static final Set<String> DIRECT_CHAINS = Set.of(
            "magnit", "magnit-kosmetik", "pyaterochka");

    /** Сети, загруженные из файла (для них переключателя нет). */
    private final Set<String> fileChains = new LinkedHashSet<>();
    private final Set<String> enabledChains = new LinkedHashSet<>();

    /** Переключатели сетей: нажатая кнопка — сеть проверяется. */
    private final Map<String, JToggleButton> chainBoxes = new LinkedHashMap<>();

    private List<Store> stores = Collections.emptyList();
    /** Сети, данные которых уже применялись: попадут в источник changeset. */
    private final Set<String> usedSources = new LinkedHashSet<>();
    /** Временная подсветка объекта после «Показать на карте». */
    private transient OsmPrimitive flashed;
    private transient boolean flashedWasOn;
    private transient javax.swing.Timer flashTimer;
    private transient StoreMarker storeMarker;

    public ReviewPanel() {
        super("Сетевые магазины", "chainpoi",
                "Сверка магазинов торговых сетей с данными OSM", null, 460, true);
        this.checkAction = new CheckAction(this);
        this.detail = new ReviewDetailPanel(new DetailListener());

        JPanel content = new JPanel(new BorderLayout(0, 2));

        JPanel top = new JPanel(new BorderLayout(6, 0));
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        topButtons.add(checkButton);
        topButtons.add(fileButton);
        top.add(topButtons, BorderLayout.WEST);
        top.add(status, BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(0, 2));
        north.add(top, BorderLayout.NORTH);
        north.add(buildChainRow(), BorderLayout.SOUTH);
        content.add(north, BorderLayout.NORTH);

        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        int[] widths = {26, 140, 150, 40, 140, 48};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(26);
        table.getColumnModel().getColumn(3).setMaxWidth(46);
        table.getColumnModel().getColumn(PANO_COLUMN).setMaxWidth(56);
        table.getColumnModel().getColumn(PANO_COLUMN).setCellRenderer(new PanoRenderer());
        table.setDefaultRenderer(String.class, new StatusRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                updateDetail();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                ReviewTableModel.Row row = model.getRow(table.convertRowIndexToModel(viewRow));
                if (row == null) {
                    return;
                }
                if (table.convertColumnIndexToModel(viewCol) == PANO_COLUMN) {
                    openPanorama(row);
                } else if (e.getClickCount() == 2) {
                    showOnMap(row);
                }
            }
        });
        addRowMenu();

        JPanel center = new JPanel(new BorderLayout(0, 2));
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setMinimumSize(new java.awt.Dimension(100, 60));
        detail.setPreferredSize(new java.awt.Dimension(320, 205));
        detail.setMinimumSize(new java.awt.Dimension(100, 120));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, detail);
        split.setResizeWeight(0.35);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setDividerSize(6);
        split.setDividerLocation(0.42);
        center.add(split, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        applyButton.setPreferredSize(new java.awt.Dimension(140, 24));
        JPanel bottom = new JPanel(new BorderLayout(6, 0));
        bottom.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 4, 0, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.add(applyButton);
        bottom.add(left, BorderLayout.WEST);
        hint.setForeground(BLUE);
        bottom.add(hint, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);

        checkButton.addActionListener(e -> checkArea());
        checkButton.setToolTipText("Запросить магазины сетей для видимой области");
        fileButton.addActionListener(e -> loadFile());
        fileButton.setToolTipText("Загрузить точки из JSON, сохранённого в браузере "
                + "(для Ozon, Wildberries и других сетей без открытого API)");
        applyButton.addActionListener(e -> applySelected());

        createLayout(content, false, Collections.emptyList());
        updateStatus();
    }

    public CheckAction getCheckAction() {
        return checkAction;
    }

    // ------------------------------------------------------------------
    // сверка
    // ------------------------------------------------------------------

    public void checkArea() {
        if (MainApplication.getMap() == null) {
            status.setText("Карта не открыта");
            return;
        }
        MapView mv = MainApplication.getMap().mapView;
        Bounds bounds = mv.getRealBounds();
        if (bounds == null) {
            status.setText("Не удалось определить область");
            return;
        }
        double area = (bounds.getMaxLat() - bounds.getMinLat())
                * (bounds.getMaxLon() - bounds.getMinLon());
        if (area > MAX_AREA) {
            status.setText("Область слишком большая — приблизьте карту");
            return;
        }

        DataSet ds = editDataSet();
        if (ds == null) {
            status.setText("Сначала загрузите данные OSM");
            return;
        }

        checkButton.setEnabled(false);
        applyButton.setEnabled(false);
        status.setText("Запрос к Магниту…");

        Bounds query = bounds;
        new Thread(() -> {
            try {
                List<Store> fetched = new ArrayList<>();
                for (ChainProvider provider : PROVIDERS) {
                    try {
                        fetched.addAll(provider.fetch(query));
                    } catch (Exception ex) {
                        Logging.warn("chainpoi: " + provider.name() + ": " + ex.getMessage());
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    stores = fetched;
                    rebuild();
                    status.setText(foundText(fetched));
                    checkButton.setEnabled(true);
                    applyButton.setEnabled(true);
                });
            } catch (Exception ex) {
                Logging.error(ex);
                SwingUtilities.invokeLater(() -> {
                    status.setText("Ошибка: " + ex.getMessage());
                    checkButton.setEnabled(true);
                    applyButton.setEnabled(true);
                });
            }
        }, "chainpoi-fetch").start();
    }

    /** Загружает выгрузку, сохранённую букмарклетом на сайте сети. */
    private void loadFile() {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        chooser.setDialogTitle("Выгрузка из браузера (JSON)");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON", "json"));
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            PoiFile file = PoiFile.read(chooser.getSelectedFile().toPath());
            fileChains.clear();
            for (Store s : file.stores) {
                fileChains.add(s.chainId);
            }
            updateEnabledChains();
            List<Store> inView = filterToView(file.stores);
            if (inView.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "В видимой области нет точек из файла.\nПодвиньте карту и загрузите снова.",
                        "Сетевые магазины", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            stores = inView;
            rebuild();
            status.setText("Загружено " + inView.size() + " из " + file.stores.size()
                    + " точек: " + chooser.getSelectedFile().getName());
        } catch (Exception ex) {
            Logging.error(ex);
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Не удалось прочитать файл:\n" + ex.getMessage(),
                    "Сетевые магазины", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Оставляет точки в видимой области (с запасом), чтобы не сверять тысячи объектов. */
    private static List<Store> filterToView(List<Store> all) {
        if (MainApplication.getMap() == null) {
            return all;
        }
        Bounds view = MainApplication.getMap().mapView.getRealBounds();
        if (view == null) {
            return all;
        }
        double margin = Math.max(view.getHeight(), view.getWidth()) * 0.5;
        Bounds wide = new Bounds(
                view.getMinLat() - margin, view.getMinLon() - margin,
                view.getMaxLat() + margin, view.getMaxLon() + margin);
        List<Store> out = new ArrayList<>();
        for (Store s : all) {
            if (wide.contains(new LatLon(s.lat, s.lon))) {
                out.add(s);
            }
        }
        return out;
    }

    /** Ряд переключателей под кнопками: какие сети проверять. */
    private JPanel buildChainRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Profile profile : Profiles.all()) {
            javax.swing.Icon icon = org.openstreetmap.josm.tools.ImageProvider.get(iconFor(profile.id));
            if (!DIRECT_CHAINS.contains(profile.id)) {
                javax.swing.JLabel hintLabel = new javax.swing.JLabel(profile.name, greyIcon(icon),
                        javax.swing.SwingConstants.LEADING);
                hintLabel.setForeground(new java.awt.Color(0x99, 0x99, 0x99));
                hintLabel.setIconTextGap(4);
                hintLabel.setToolTipText("В разработке: источника данных пока нет — "
                        + "только импорт из файла");
                row.add(hintLabel);
                continue;
            }
            JToggleButton box = new JToggleButton(profile.name, icon);
            box.putClientProperty("icon-on", icon);
            box.putClientProperty("icon-off", greyIcon(icon));
            box.setSelected(true);
            box.setFocusable(false);
            box.setMargin(new java.awt.Insets(1, 4, 1, 4));
            box.setToolTipText("Проверять сеть «" + profile.name + "»");
            box.addActionListener(e -> {
                styleChain(box);
                updateEnabledChains();
                rebuild();
            });
            styleChain(box);
            chainBoxes.put(profile.id, box);
            enabledChains.add(profile.id);
            row.add(box);
        }
        return row;
    }

    /** Выключенная сеть — серая иконка и серый текст. */
    private static void styleChain(JToggleButton box) {
        boolean on = box.isSelected();
        box.setIcon((javax.swing.Icon) box.getClientProperty(on ? "icon-on" : "icon-off"));
        box.setForeground(on ? javax.swing.UIManager.getColor("Button.foreground")
                : new java.awt.Color(0x99, 0x99, 0x99));
    }

    private static javax.swing.Icon greyIcon(javax.swing.Icon icon) {
        if (icon instanceof javax.swing.ImageIcon) {
            java.awt.Image grey = javax.swing.GrayFilter.createDisabledImage(
                    ((javax.swing.ImageIcon) icon).getImage());
            return new javax.swing.ImageIcon(grey);
        }
        return icon;
    }

    private void updateEnabledChains() {
        enabledChains.clear();
        for (Map.Entry<String, JToggleButton> e : chainBoxes.entrySet()) {
            if (e.getValue().isSelected()) {
                enabledChains.add(e.getKey());
            }
        }
        enabledChains.addAll(fileChains);
    }

    private static String iconFor(String chainId) {
        switch (chainId) {
            case "magnit":
                return "chain-magnit";
            case "magnit-kosmetik":
                return "chain-kosmetik";
            case "pyaterochka":
                return "chain-pyaterochka";
            case "ozon":
                return "chain-ozon";
            case "ozon-box":
                return "chain-ozon-box";
            case "wildberries":
                return "chain-wildberries";
            default:
                return "chainpoi";
        }
    }

    /** Пересобирает таблицу по уже загруженным данным сети, без обращения к сайту. */
    private void rebuild() {
        DataSet ds = editDataSet();
        List<Store> active = new ArrayList<>();
        for (Store s : stores) {
            if (enabledChains.contains(s.chainId)) {
                active.add(s);
            }
        }
        List<ReviewTableModel.Row> rows = new ArrayList<>();
        for (Store s : active) {
            rows.add(new ReviewTableModel.Row(s, Matcher.check(s, ds)));
        }
        for (Match orphan : Matcher.orphans(active, ds)) {
            rows.add(new ReviewTableModel.Row(null, orphan));
        }
        rows.sort(Comparator
                .comparingInt((ReviewTableModel.Row r) -> r.reviewed ? 1 : 0)
                .thenComparingInt(r -> rank(r.match)));
        model.setRows(rows);
        updateStatus();
        if (!rows.isEmpty() && table.getSelectedRow() < 0) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    /** «Найдено 14: Магнит 5, М.Косметик 4, Пятёрочка 5». */
    private static String foundText(List<Store> found) {
        Map<String, Integer> byChain = new LinkedHashMap<>();
        for (Store s : found) {
            byChain.merge(s.chainName, 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Найдено ").append(found.size());
        if (!byChain.isEmpty()) {
            sb.append(": ");
            boolean first = true;
            for (Map.Entry<String, Integer> e : byChain.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append(' ').append(e.getValue());
                first = false;
            }
        }
        return sb.toString();
    }

    private static int rank(Match m) {
        if (m == null) {
            return 5;
        }
        switch (m.status) {
            case MISSING:
                return 0;
            case DIFF:
                return 1;
            case BAD:
                return 2;
            case ORPHAN:
                return 3;
            case OK:
                return 4;
            case OTHER_FORMAT:
                return m.diffs.isEmpty() ? 5 : 1;
            default:
                return 5;
        }
    }

    private void updateStatus() {
        int missing = 0;
        int diff = 0;
        int bad = 0;
        int ok = 0;
        int orphan = 0;
        int other = 0;
        for (ReviewTableModel.Row r : model.getRows()) {
            if (r.match == null) {
                continue;
            }
            switch (r.match.status) {
                case MISSING:
                    missing++;
                    break;
                case DIFF:
                    diff++;
                    break;
                case BAD:
                    bad++;
                    break;
                case OK:
                    ok++;
                    break;
                case ORPHAN:
                    orphan++;
                    break;
                case OTHER_FORMAT:
                    other++;
                    break;
                default:
                    break;
            }
        }
        status.setText("Всего " + model.getRows().size()
                + ": нет в OSM " + missing + ", расхождения " + diff
                + ", ошибки " + bad + ", ок " + ok + ", нет в сети " + orphan
                + ", другое " + other);
    }

    // ------------------------------------------------------------------
    // применение
    // ------------------------------------------------------------------

    private void applySelected() {
        DataSet ds = editDataSet();
        if (ds == null) {
            return;
        }
        Applier.Result result = Applier.build(ds, model.getRows());
        if (result.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Нечего применять.",
                    "Сетевые магазины", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(this, planText(result), "Сетевые магазины",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        List<ReviewTableModel.Row> applied = new ArrayList<>();
        for (ReviewTableModel.Row row : model.getRows()) {
            if (row.selected && row.match != null && row.match.canApply()) {
                applied.add(row);
            }
        }
        noteSources(ds, applied);
        run(result);
        rebuild();
    }

    /** Применяет одну строку — кнопка «Принять» в панели деталей. */
    private void applyRow(ReviewTableModel.Row row) {
        DataSet ds = editDataSet();
        if (ds == null) {
            return;
        }
        Applier.Result result = Applier.buildRow(ds, row);
        if (result.isEmpty()) {
            hint.setText("Нечего применять.");
            return;
        }
        noteSources(ds, List.of(row));
        run(result);
        row.selected = false;
        row.reviewed = true;

        if (row.store != null) {
            row.match = Matcher.check(row.store, ds);
        } else if (result.closed > 0) {
            model.getRows().remove(row);
        }
        resort();
        updateStatus();
        selectRow(row);
        updateDetail();
        moveToNextReview();
    }

    private void run(Applier.Result result) {
        UndoRedoHandler.getInstance().add(new SequenceCommand(summary(result), result.commands));
        DataSet ds = editDataSet();
        if (ds != null && !result.touched.isEmpty()) {
            ds.setSelected(result.touched);
        }
        showReminder(result);
    }

    /** Запоминает сайты сетей, данные которых применили: пополняет тег changeset. */
    private void noteSources(DataSet ds, java.util.Collection<ReviewTableModel.Row> rows) {
        if (ds == null) {
            return;
        }
        for (ReviewTableModel.Row row : rows) {
            if (row.store != null && row.store.chainId != null) {
                usedSources.add(row.store.chainId);
            }
        }
        String urls = Sources.join(usedSources);
        if (!urls.isEmpty()) {
            ds.addChangeSetTag(Sources.TAG, urls);
        }
    }

    /** Разобранные и пропущенные строки — вниз, остальные по важности. */
    private void resort() {
        model.getRows().sort(Comparator
                .comparingInt((ReviewTableModel.Row r) -> r.reviewed ? 1 : 0)
                .thenComparingInt(r -> rank(r.match)));
        model.fireTableDataChanged();
    }

    private String summary(Applier.Result r) {
        StringBuilder sb = new StringBuilder("Сетевые магазины");
        if (r.created > 0) {
            sb.append(": создано ").append(r.created);
        }
        if (r.updated > 0) {
            sb.append(": дополнено ").append(r.updated);
        }
        if (r.closed > 0) {
            sb.append(": закрыто ").append(r.closed);
        }
        return sb.toString();
    }

    private void showReminder(Applier.Result r) {
        String ref = r.touched.isEmpty() ? "" : ReviewTableModel.ref(r.touched.get(0));
        if ("новый".equals(ref)) {
            ref = "новая точка";
        }
        String text;
        if (r.created > 0) {
            text = "Создано: " + ref + ". Точка стоит по координатам сети — проверьте положение, "
                    + "её можно перетащить мышью.";
        } else if (r.closed > 0) {
            text = "Отмечено закрытым: " + ref + ".";
        } else {
            String keys = r.changedKeys.isEmpty() ? ""
                    : " (" + String.join(", ", r.changedKeys) + ")";
            text = "Изменено: " + ref + keys + ". Объект выделен на карте.";
        }
        hint.setText("<html><body style='width:300px'>" + text + "</body></html>");
        hint.setForeground(BLUE);
    }

    /** Что именно будет сделано — для окна подтверждения. */
    private String planText(Applier.Result r) {
        StringBuilder sb = new StringBuilder("<html><body style='width:420px'>");
        if (r.created > 0) {
            sb.append("Создать точек: ").append(r.created).append("<br>");
        }
        if (r.updated > 0) {
            sb.append("Дополнить объектов: ").append(r.updated).append("<br>");
        }
        if (r.closed > 0) {
            sb.append("Закрыть магазинов: ").append(r.closed).append("<br>");
        }
        sb.append("<br>");
        int shown = 0;
        for (ReviewTableModel.Row row : model.getRows()) {
            if (!row.selected || row.match == null || !row.match.canApply()) {
                continue;
            }
            if (shown >= 12) {
                sb.append("…<br>");
                break;
            }
            sb.append("• ").append(row.title());
            if (row.match.status == Match.Status.MISSING) {
                sb.append(" — создать точку");
            } else if (row.match.status == Match.Status.ORPHAN) {
                sb.append(" — закрылся?");
            } else {
                List<String> keys = new ArrayList<>();
                for (FieldDiff d : row.match.diffs) {
                    if (d.accepted) {
                        keys.add(d.key);
                    }
                }
                if (!keys.isEmpty()) {
                    sb.append(" — ").append(String.join(", ", keys));
                }
            }
            sb.append("<br>");
            shown++;
        }
        sb.append("<br>Применить? Загрузку на сервер делаете сами.</body></html>");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // навигация
    // ------------------------------------------------------------------

    private ReviewTableModel.Row selectedRow() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return null;
        }
        int index = table.convertRowIndexToModel(viewRow);
        return model.getRow(index);
    }

    private void selectRow(ReviewTableModel.Row row) {
        for (int i = 0; i < model.getRows().size(); i++) {
            if (model.getRows().get(i) == row) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private void moveSelection(int delta) {
        int size = model.getRows().size();
        if (size == 0) {
            return;
        }
        int current = table.getSelectedRow();
        int next = Math.max(0, Math.min(size - 1, current < 0 ? 0 : current + delta));
        table.setRowSelectionInterval(next, next);
        table.scrollRectToVisible(table.getCellRect(next, 0, true));
    }

    private void moveToNextReview() {
        int size = model.getRows().size();
        if (size == 0) {
            return;
        }
        int start = Math.max(0, table.getSelectedRow());
        for (int step = 1; step <= size; step++) {
            int i = (start + step) % size;
            ReviewTableModel.Row row = model.getRows().get(i);
            if (row.match != null && !row.reviewed && needsAttention(row.match)) {
                table.setRowSelectionInterval(i, i);
                table.scrollRectToVisible(table.getCellRect(i, 0, true));
                return;
            }
        }
    }

    private static boolean needsAttention(Match m) {
        return m.status == Match.Status.MISSING || m.status == Match.Status.DIFF
                || m.status == Match.Status.BAD || m.status == Match.Status.ORPHAN
                || (m.status == Match.Status.OTHER_FORMAT && !m.diffs.isEmpty());
    }

    private void updateDetail() {
        ReviewTableModel.Row row = selectedRow();
        detail.setRow(row, editDataSet());
    }

    /**
     * Правый клик по строке. Короткий клик — обычное меню; зажатие (или сдвиг)
     * открывает «цветок»: ведём к лепестку и отпускаем кнопку.
     */
    private void addRowMenu() {
        MouseAdapter gestures = new MouseAdapter() {
            private javax.swing.Timer hold;
            private ReviewTableModel.Row row;
            private Point press;

            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) {
                    return;
                }
                int viewRow = table.rowAtPoint(e.getPoint());
                if (viewRow < 0) {
                    return;
                }
                table.setRowSelectionInterval(viewRow, viewRow);
                row = model.getRow(table.convertRowIndexToModel(viewRow));
                if (row == null) {
                    return;
                }
                press = e.getPoint();
                PieMenu.resetSuppress();
                if (hold != null) {
                    hold.stop();
                }
                hold = new javax.swing.Timer(140, ev -> {
                    hold.stop();
                    openPie(row, press);
                });
                hold.setRepeats(false);
                hold.start();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e) || press == null
                        || hold == null || !hold.isRunning()) {
                    return;
                }
                if (e.getPoint().distance(press) > 8) {
                    hold.stop();
                    openPie(row, press);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (!SwingUtilities.isRightMouseButton(e)) {
                    return;
                }
                if (hold != null) {
                    hold.stop();
                }
                if (PieMenu.suppressed()) {
                    press = null;
                    return;
                }
                if (row != null && press != null) {
                    buildRowMenu(row).show(table, e.getX(), e.getY());
                }
                press = null;
            }
        };
        table.addMouseListener(gestures);
        table.addMouseMotionListener(gestures);
    }

    /** Открывает цветок в точке нажатия (экранные координаты). */
    private void openPie(ReviewTableModel.Row row, Point at) {
        if (at == null) {
            return;
        }
        Point screen = new Point(at);
        SwingUtilities.convertPointToScreen(screen, table);
        Window owner = SwingUtilities.getWindowAncestor(table);
        if (!PieMenu.showRadial(owner, rowItems(row), screen)) {
            buildRowMenu(row).show(table, at.x, at.y);
        }
    }

    /** Действия над строкой — общие для обычного меню и «цветка». */
    private List<PieMenu.Item> rowItems(ReviewTableModel.Row row) {
        Match m = row.match;
        OsmPrimitive p = m == null ? null : m.primitive;

        boolean canAccept = m != null && m.canApply()
                && (m.status == Match.Status.MISSING || m.status == Match.Status.ORPHAN
                    || hasAccepted(row));
        boolean canSkip = m != null && m.status != Match.Status.OK;
        boolean canLocate = p != null || row.store != null;

        List<PieMenu.Item> items = new ArrayList<>();
        items.add(new PieMenu.Item("Принять", "Принять", PieMenu.Kind.ACCEPT,
                canAccept, () -> applyRow(row)));
        items.add(new PieMenu.Item("Пропустить", "Пропустить", PieMenu.Kind.SKIP,
                canSkip, () -> skipRow(row)));
        items.add(new PieMenu.Item("Показать на карте", "Показать", PieMenu.Kind.SHOW,
                canLocate, () -> showOnMap(row)));
        items.add(new PieMenu.Item("Выделить на карте", "Выделить", PieMenu.Kind.SELECT,
                p != null, () -> {
                    DataSet ds = editDataSet();
                    if (ds != null && p != null) {
                        ds.setSelected(p);
                        ensureVisible(p);
                    }
                }));
        items.add(new PieMenu.Item("Панорамы", "Панорамы", PieMenu.Kind.PANO,
                canLocate, () -> openPanorama(row)));
        return items;
    }

    private javax.swing.JPopupMenu buildRowMenu(ReviewTableModel.Row row) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        List<PieMenu.Item> items = rowItems(row);
        for (int i = 0; i < items.size(); i++) {
            PieMenu.Item item = items.get(i);
            javax.swing.JMenuItem mi = new javax.swing.JMenuItem(item.label);
            mi.setEnabled(item.enabled);
            mi.addActionListener(e -> item.action.run());
            menu.add(mi);
            if (i == 1) {
                menu.addSeparator();
            }
        }
        return menu;
    }

    /** Пропустить строку: снять галочку, увести вниз и перейти к следующей. */
    private void skipRow(ReviewTableModel.Row row) {
        row.selected = false;
        row.reviewed = true;
        resort();
        selectRow(row);
        updateDetail();
        moveToNextReview();
    }

    private static boolean hasAccepted(ReviewTableModel.Row row) {
        if (row.match == null) {
            return false;
        }
        if (row.match.status == Match.Status.MISSING || row.match.status == Match.Status.ORPHAN) {
            return true;
        }
        for (FieldDiff d : row.match.diffs) {
            if (d.accepted) {
                return true;
            }
        }
        return false;
    }

    /** Выделяет объект, а если он вне кадра — подводит карту без смены масштаба. */
    private static void ensureVisible(OsmPrimitive p) {
        MapView mv = MainApplication.getMap() == null ? null : MainApplication.getMap().mapView;
        if (mv == null) {
            return;
        }
        org.openstreetmap.josm.data.osm.BBox bb = p.getBBox();
        LatLon c = bb.getCenter();
        if (c == null) {
            return;
        }
        Bounds view = mv.getRealBounds();
        if (view != null && view.contains(c)) {
            return;
        }
        double spanLat = view == null ? POINT_SPAN_DEG : view.getHeight();
        double spanLon = view == null ? POINT_SPAN_DEG : view.getWidth();
        mv.zoomTo(new Bounds(c, spanLat, spanLon));
    }

    /** Переход к магазину: только центрирование и масштаб, без выделения. */
    private void showOnMap(ReviewTableModel.Row row) {
        if (MainApplication.getMap() == null || row == null || row.match == null) {
            return;
        }
        OsmPrimitive p = row.match.primitive;
        LatLon center;
        double span;
        if (p != null) {
            org.openstreetmap.josm.data.osm.BBox bb = p.getBBox();
            center = bb.getCenter();
            double objectSize = Math.max(bb.getHeight(), bb.getWidth());
            span = Math.max(POINT_SPAN_DEG, objectSize * 3);
        } else if (row.store != null) {
            center = new LatLon(row.store.lat, row.store.lon);
            span = POINT_SPAN_DEG;
        } else {
            return;
        }
        if (center == null) {
            return;
        }
        MainApplication.getMap().mapView.zoomTo(new Bounds(center, span, span));
        if (p != null) {
            flash(p);
        } else if (row.store != null) {
            showStoreMarker(row.store);
        }
    }

    /** Значок сети с ореолом в точке, где магазина в OSM ещё нет. */
    private void showStoreMarker(Store store) {
        MapView mv = MainApplication.getMap() == null ? null : MainApplication.getMap().mapView;
        if (mv == null) {
            return;
        }
        if (storeMarker != null) {
            storeMarker.cancel();
            storeMarker = null;
        }
        javax.swing.ImageIcon icon =
                org.openstreetmap.josm.tools.ImageProvider.get(iconFor(store.chainId));
        StoreMarker marker = new StoreMarker(mv, new LatLon(store.lat, store.lon),
                icon, StoreMarker.colorFor(store.chainId));
        marker.setOnFinish(() -> {
            if (storeMarker == marker) {
                storeMarker = null;
            }
        });
        storeMarker = marker;
        mv.addTemporaryLayer(marker);
        marker.start();
    }

    /** Кратко подсвечивает объект, не меняя выделение. */
    private void flash(OsmPrimitive p) {
        if (p == null) {
            return;
        }
        if (flashTimer != null) {
            flashTimer.stop();
        }
        clearFlash();
        flashed = p;
        flashedWasOn = p.isHighlighted();
        p.setHighlighted(true);
        flashTimer = new javax.swing.Timer(2600, e -> clearFlash());
        flashTimer.setRepeats(false);
        flashTimer.start();
    }

    private void clearFlash() {
        if (flashed != null) {
            flashed.setHighlighted(flashedWasOn);
            flashed = null;
        }
        flashTimer = null;
    }

    private static DataSet editDataSet() {
        return MainApplication.getLayerManager().getEditDataSet();
    }

    /**
     * Открывает Яндекс.Карты со слоем панорам у магазина (как букмарклет).
     *
     * <p>Смотреть карту можно, но брать из неё данные нельзя: по правилам OSM
     * использовать разрешено только фотографии панорам — без подложки,
     * адресных меток и спутниковых снимков.
     */
    private void openPanorama(ReviewTableModel.Row row) {
        if (row == null || row.match == null) {
            return;
        }
        LatLon c = null;
        if (row.match.primitive != null) {
            c = row.match.primitive.getBBox().getCenter();
        }
        if (c == null && row.store != null) {
            c = new LatLon(row.store.lat, row.store.lon);
        }
        if (c == null) {
            return;
        }
        String point = String.format(java.util.Locale.ROOT, "%.6f,%.6f", c.lon(), c.lat());
        // Как в букмарклете: спутник + слой панорам, центр на магазине.
        // Карта нужна только чтобы дойти до панорамы, данные с неё не берём.
        String url = "https://yandex.ru/maps/?l=sat%2Cskl%2Cstv%2Csta&ll=" + point + "&z=18";
        String error = org.openstreetmap.josm.tools.OpenBrowser.displayUrl(url);
        if (error != null) {
            hint.setText("Не удалось открыть браузер: " + error);
        }
    }

    // ------------------------------------------------------------------
    // вспомогательное
    // ------------------------------------------------------------------

    private final class DetailListener implements ReviewDetailPanel.Listener {
        @Override
        public void onAccept(ReviewTableModel.Row row) {
            applyRow(row);
        }

        @Override
        public void onSkip(ReviewTableModel.Row row) {
            skipRow(row);
        }

        @Override
        public void onNavigate(int delta) {
            moveSelection(delta);
        }

        @Override
        public void onShow(ReviewTableModel.Row row) {
            showOnMap(row);
        }

        @Override
        public void onPanorama(ReviewTableModel.Row row) {
            openPanorama(row);
        }

        @Override
        public void onClose(ReviewTableModel.Row row) {
            applyRow(row);
        }
    }

    /** Кнопка «Пан.» в строке таблицы. Клик обрабатывается слушателем таблицы. */
    private static final class PanoRenderer extends javax.swing.JButton
            implements javax.swing.table.TableCellRenderer {

        PanoRenderer() {
            super(org.openstreetmap.josm.tools.ImageProvider.get("panoramas"));
            setMargin(new java.awt.Insets(0, 2, 0, 2));
            setFocusable(false);
            setToolTipText("Открыть панорамы у этого магазина");
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }

    /** Красит статус по смыслу. */
    private final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            c.setForeground(isSelected ? t.getSelectionForeground() : t.getForeground());
            if (!isSelected && column == 1) {
                ReviewTableModel.Row r = model.getRow(t.convertRowIndexToModel(row));
                if (r != null && r.match != null) {
                    switch (r.match.status) {
                        case MISSING:
                            c.setForeground(RED);
                            break;
                        case DIFF:
                            c.setForeground(ORANGE);
                            break;
                        case BAD:
                            c.setForeground(RED);
                            break;
                        case OK:
                            c.setForeground(GREEN);
                            break;
                        case ORPHAN:
                            c.setForeground(VIOLET);
                            break;
                        default:
                            break;
                    }
                }
            }
            return c;
        }
    }

    /**
     * Пункт меню «Данные → Проверить сетевые магазины».
     */
    public static final class CheckAction extends JosmAction {
        private final transient ReviewPanel panel;

        CheckAction(ReviewPanel panel) {
            super("Проверить сетевые магазины", "chainpoi",
                    "Запросить магазины сети для видимой области",
                    Shortcut.registerShortcut("tools:chainpoi:check",
                            "Инструменты: проверить сетевые магазины",
                            KeyEvent.VK_F9, Shortcut.CTRL_SHIFT), true);
            this.panel = panel;
        }

        @Override
        public void actionPerformed(ActionEvent e) {
            panel.showDialog();
            panel.checkArea();
        }
    }
}
