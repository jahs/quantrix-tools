package com.example.quantrix;

import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import javax.swing.JOptionPane;

import com.subx.framework.IPlugin;
import com.subx.general.ui.api.actions.AbstractContextAction;
import com.subx.general.ui.api.actions.XActionEvent;
import com.subx.general.ui.api.actions.context.IActionContext;

public class HelloPlugin extends IPlugin.Adapter {

    public static final String PLUGIN_ID = "com.example.quantrix.hello";

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    public static final class HelloAction extends AbstractContextAction {

        @Override
        public void performAction(IActionContext context, XActionEvent event) {
            JOptionPane.showMessageDialog(
                findParentWindow(),
                "Hello from a Quantrix plugin.\n\nThis is a minimal example wired into the current 24.4 plugin system.",
                "Quantrix Plugin Example",
                JOptionPane.INFORMATION_MESSAGE
            );
        }

        private Component findParentWindow() {
            Window[] windows = Window.getWindows();
            for (Window window : windows) {
                if (window.isVisible() && window instanceof Frame) {
                    return window;
                }
            }
            return null;
        }
    }
}
