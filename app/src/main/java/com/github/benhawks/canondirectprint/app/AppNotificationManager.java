package com.github.benhawks.canondirectprint.app;

import java.util.ArrayList;

/** Tiny in-process event bus (from PMCADemo). */
public class AppNotificationManager {
    public interface NotificationListener {
        void onNotify(String message);
    }

    private static final AppNotificationManager instance = new AppNotificationManager();

    public static AppNotificationManager getInstance() {
        return instance;
    }

    private final ArrayList<NotificationListener> listeners = new ArrayList<NotificationListener>();

    private AppNotificationManager() {}

    public void notify(String message) {
        for (NotificationListener listener : new ArrayList<NotificationListener>(listeners))
            listener.onNotify(message);
    }

    public void addListener(NotificationListener listener) {
        listeners.add(listener);
    }

    public void removeListener(NotificationListener listener) {
        listeners.remove(listener);
    }
}
