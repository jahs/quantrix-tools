package com.subx.document.ui.api;

public interface XDocumentApplicationUI {

    void addListener(Listener listener);
    void removeListener(Listener listener);

    interface Listener {
        void uiApplicationDocumentOpened(XDocumentApplicationUI app, XDocumentFrame frame);
    }

    class ListenerAdapter implements Listener {
        @Override
        public void uiApplicationDocumentOpened(XDocumentApplicationUI app, XDocumentFrame frame) {}
    }
}
