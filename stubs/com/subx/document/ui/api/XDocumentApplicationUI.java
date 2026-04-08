package com.subx.document.ui.api;

import com.subx.general.core.api.notification.IListener;
import com.subx.general.core.api.notification.INotifier;

public interface XDocumentApplicationUI extends INotifier {

    interface Listener extends IListener {
        void uiApplicationDocumentOpened(XDocumentApplicationUI app, XDocumentFrame frame);
        void uiApplicationDocumentClosed(XDocumentApplicationUI app, XDocumentFrame frame);
        void uiApplicationDocumentActivated(XDocumentApplicationUI app, XDocumentFrame frame);
        void uiApplicationDocumentDeactivated(XDocumentApplicationUI app, XDocumentFrame frame);
        void uiApplicationActivated(XDocumentApplicationUI app);
        void uiApplicationDeactivated(XDocumentApplicationUI app);
        void uiApplicationUncaughtException(XDocumentApplicationUI app, Throwable t);
        void uiApplicationRecentFilesWillChange(XDocumentApplicationUI app);
        void uiApplicationRecentFilesDidChange(XDocumentApplicationUI app);
        void uiApplicationDidLaunch(XDocumentApplicationUI app);
    }

    class ListenerAdapter implements Listener {
        public void uiApplicationDocumentOpened(XDocumentApplicationUI app, XDocumentFrame frame) {}
        public void uiApplicationDocumentClosed(XDocumentApplicationUI app, XDocumentFrame frame) {}
        public void uiApplicationDocumentActivated(XDocumentApplicationUI app, XDocumentFrame frame) {}
        public void uiApplicationDocumentDeactivated(XDocumentApplicationUI app, XDocumentFrame frame) {}
        public void uiApplicationActivated(XDocumentApplicationUI app) {}
        public void uiApplicationDeactivated(XDocumentApplicationUI app) {}
        public void uiApplicationUncaughtException(XDocumentApplicationUI app, Throwable t) {}
        public void uiApplicationRecentFilesWillChange(XDocumentApplicationUI app) {}
        public void uiApplicationRecentFilesDidChange(XDocumentApplicationUI app) {}
        public void uiApplicationDidLaunch(XDocumentApplicationUI app) {}
    }
}
