package org.iaproject.memchat.tui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;

final class TuiClipboard {
    private TuiClipboard() {}

    static String readText() {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Presse-papiers indisponible ici : utilisez le collage de votre terminal");
        }
        try {
            var clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) return "";
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        } catch (IOException | UnsupportedFlavorException | IllegalStateException | SecurityException exception) {
            throw new IllegalStateException("Presse-papiers inaccessible : utilisez le collage de votre terminal", exception);
        }
    }
}
