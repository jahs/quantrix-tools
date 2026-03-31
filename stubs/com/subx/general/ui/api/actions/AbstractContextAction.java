package com.subx.general.ui.api.actions;

import com.subx.general.ui.api.actions.context.IActionContext;

public abstract class AbstractContextAction {
    public abstract void performAction(IActionContext context, XActionEvent event);
}
