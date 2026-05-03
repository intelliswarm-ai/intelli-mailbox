package ai.intelliswarm.intellimailbox.system;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.awt.AWTException;
import java.awt.Desktop;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import javax.imageio.ImageIO;

/**
 * Adds a system-tray icon so non-technical users have an obvious way to
 * quit Intelli-mailbox. Closing the browser tab does NOT stop the app —
 * the JVM keeps running and serving Spring Boot — so without a tray icon
 * users have no idea the app is still consuming RAM until they find Task
 * Manager. Standard pattern for "background web service with browser UI"
 * apps (Plex, Calibre, OBS, Discord all do this).
 *
 * <p>Tray menu:
 * <ul>
 *   <li><b>Open Intelli-mailbox</b> — re-opens the UI tab in default browser</li>
 *   <li><b>Settings…</b> — opens the UI then pings the in-page settings modal</li>
 *   <li><b>About Intelli-mailbox</b> — opens the product page</li>
 *   <li><b>Quit Intelli-mailbox</b> — clean Spring shutdown</li>
 * </ul>
 *
 * <p>Falls back gracefully on systems where {@code SystemTray.isSupported()}
 * returns false (older Linux desktops, some headless setups). The Quit
 * button inside the app's Settings dialog is the backup path for those.
 */
@Component
public class SystemTrayManager {

    private static final Logger logger = LoggerFactory.getLogger(SystemTrayManager.class);

    @Value("${server.port:8090}")
    private int serverPort;

    private final ConfigurableApplicationContext context;
    private TrayIcon trayIcon;

    public SystemTrayManager(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void install() {
        if (!SystemTray.isSupported()) {
            logger.info("System tray not supported on this OS — Quit-from-tray unavailable. "
                    + "Use the Quit button in the app's Settings dialog instead.");
            return;
        }
        try {
            Image trayImage = loadTrayImage();
            if (trayImage == null) {
                logger.warn("Couldn't load tray icon image; tray icon won't appear");
                return;
            }

            PopupMenu popup = new PopupMenu();

            MenuItem openItem = new MenuItem("Open Intelli-mailbox");
            openItem.addActionListener(e -> openInBrowser(""));
            popup.add(openItem);

            MenuItem settingsItem = new MenuItem("Settings…");
            settingsItem.addActionListener(e -> openInBrowser("?openSettings=1"));
            popup.add(settingsItem);

            popup.addSeparator();

            MenuItem aboutItem = new MenuItem("About Intelli-mailbox");
            aboutItem.addActionListener(e -> openExternal("https://intelliswarm.ai/products/intelli-mailbox"));
            popup.add(aboutItem);

            popup.addSeparator();

            MenuItem quitItem = new MenuItem("Quit Intelli-mailbox");
            quitItem.addActionListener(e -> quit());
            popup.add(quitItem);

            trayIcon = new TrayIcon(trayImage, "Intelli Mailbox - running", popup);
            trayIcon.setImageAutoSize(true);
            // Double-click on the icon = open the UI.
            trayIcon.addActionListener(e -> openInBrowser(""));

            SystemTray.getSystemTray().add(trayIcon);
            logger.info("System tray icon installed; right-click for menu, double-click to open UI");

            // First-launch hint so users discover the tray icon.
            try {
                trayIcon.displayMessage(
                        "Intelli-mailbox is running",
                        "Right-click this icon to open or quit. Closing the browser tab "
                                + "doesn't stop the app — only Quit (here) does.",
                        TrayIcon.MessageType.INFO);
            } catch (Exception ignored) { /* tray notifications are best-effort */ }

        } catch (AWTException e) {
            logger.warn("Couldn't install system tray icon ({}). "
                    + "The Quit button in the app's Settings dialog will still work.", e.getMessage());
        } catch (Exception e) {
            logger.warn("Unexpected error installing tray icon: {}", e.toString());
        }
    }

    @PreDestroy
    public void teardown() {
        if (trayIcon != null) {
            try { SystemTray.getSystemTray().remove(trayIcon); }
            catch (Exception ignored) { }
            trayIcon = null;
        }
    }

    private Image loadTrayImage() {
        // The intelliswarm-logo.png lives in static/ and is served by the web
        // server too — bundling it in the jar is enough; we don't need a
        // separate tray-specific asset until we want OS-themed icons.
        try (InputStream in = getClass().getResourceAsStream("/static/intelliswarm-logo.png")) {
            if (in == null) return null;
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            // 16x16 is a sane default; setImageAutoSize handles HiDPI scaling.
            return img.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
        } catch (Exception e) {
            logger.debug("Tray image load failed: {}", e.toString());
            return null;
        }
    }

    private void openInBrowser(String suffix) {
        openExternal("http://localhost:" + serverPort + "/" + suffix);
    }

    private void openExternal(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            logger.debug("Couldn't open {}: {}", url, e.toString());
        }
    }

    private void quit() {
        logger.info("Quit requested via system tray — shutting down Spring");
        // Off-thread so the AWT event dispatch thread doesn't block on Spring
        // shutdown (which can take a couple of seconds while it tears down
        // Tomcat + the enricher loop).
        new Thread(() -> {
            try {
                int code = SpringApplication.exit(context, () -> 0);
                System.exit(code);
            } catch (Throwable t) {
                logger.warn("Tray-Quit shutdown errored: {}", t.toString());
                System.exit(1);
            }
        }, "intellimailbox-tray-quit").start();
    }
}
