package app.perroute.platform.macos;

import java.awt.Desktop;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.desktop.AppReopenedListener;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native application events return to the desktop's existing lifecycle rather than exiting past it. */
public final class MacDesktopIntegration implements AutoCloseable {
    private final Desktop desktop;
    private final AtomicBoolean closed = new AtomicBoolean();
    private AppReopenedListener reopened;
    private boolean quitInstalled, preferencesInstalled;

    private MacDesktopIntegration(Desktop desktop) { this.desktop = desktop; }
    public static MacDesktopIntegration install(Runnable reopen, Runnable quit, Runnable preferences) {
        if (!System.getProperty("os.name", "").equals("Mac OS X") || GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported())
            return new MacDesktopIntegration(null);
        var integration = new MacDesktopIntegration(Desktop.getDesktop());
        var desktop = integration.desktop;
        try {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_REOPENED)) {
                integration.reopened = event -> integration.dispatch(reopen);
                desktop.addAppEventListener(integration.reopened);
            }
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                desktop.setQuitHandler((event, response) -> {
                    if (integration.closed.get()) response.performQuit();
                    else {
                        // Compose's exit path closes the controller and restores proxy ownership first.
                        response.cancelQuit();
                        integration.dispatch(quit);
                    }
                });
                integration.quitInstalled = true;
            }
            if (desktop.isSupported(Desktop.Action.APP_PREFERENCES)) {
                desktop.setPreferencesHandler(event -> integration.dispatch(preferences));
                integration.preferencesInstalled = true;
            }
            return integration;
        } catch (RuntimeException failure) {
            integration.close();
            return new MacDesktopIntegration(null);
        }
    }
    public boolean supportsReopen() { return reopened != null && !closed.get(); }
    private void dispatch(Runnable action) {
        EventQueue.invokeLater(() -> { if (!closed.get()) action.run(); });
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true) || desktop == null) return;
        if (reopened != null) { try { desktop.removeAppEventListener(reopened); } catch (RuntimeException ignored) { } }
        if (quitInstalled) { try { desktop.setQuitHandler(null); } catch (RuntimeException ignored) { } }
        if (preferencesInstalled) { try { desktop.setPreferencesHandler(null); } catch (RuntimeException ignored) { } }
    }
}
