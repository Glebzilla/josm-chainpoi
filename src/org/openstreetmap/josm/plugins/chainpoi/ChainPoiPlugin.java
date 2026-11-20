package org.openstreetmap.josm.plugins.chainpoi;

import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MainMenu;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.plugins.Plugin;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Logging;

/**
 * Плагин «Сетевые магазины»: сверка магазинов торговых сетей с данными OSM.
 */
public class ChainPoiPlugin extends Plugin {

    private ReviewPanel panel;
    private boolean installed;

    public ChainPoiPlugin(PluginInformation info) {
        super(info);
        // Плагин грузится до создания окна карты, поэтому mapFrameInitialized
        // от JOSM может не прийти. Слушатель сработает и сразу, если карта уже есть.
        MainApplication.addAndFireMapFrameListener(this::mapFrameInitialized);
    }

    @Override
    public void mapFrameInitialized(MapFrame oldFrame, MapFrame newFrame) {
        if (newFrame != null) {
            install(newFrame);
        }
    }

    private synchronized void install(MapFrame map) {
        if (installed) {
            return;
        }
        installed = true;

        if (panel == null) {
            panel = new ReviewPanel();
        }
        map.addToggleDialog(panel);
        if (MainApplication.getMenu() != null) {
            MainMenu.add(MainApplication.getMenu().dataMenu, panel.getCheckAction());
        }
        // showDialog() сам по себе не выделяет место в панели диалогов —
        // нужен reconstruct, который делает toggle-действие.
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (!panel.isDialogShowing()) {
                panel.getToggleAction().actionPerformed(
                        new java.awt.event.ActionEvent(panel, java.awt.event.ActionEvent.ACTION_PERFORMED, "show"));
            }
        });
        Logging.debug("chainpoi: панель добавлена");
    }
}
