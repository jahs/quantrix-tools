package com.subx.document.ui.iapi;

import com.subx.document.ui.api.XDocumentApplicationUI;

public abstract class DocumentUIApplication implements XDocumentApplicationUI {
    public static DocumentUIApplication runningInstance() { return null; }
}
