/*
 * hello-plugin.groovy — Minimal Groovy plugin for Quantrix Modeler.
 *
 * Drop this file in ~/Library/Application Support/Quantrix/groovy-plugins/
 * and it will be loaded automatically by the Groovy Loader Plugin.
 *
 * Use Plugins → Manage Groovy Plugins → Reload to pick up changes
 * without restarting Quantrix.
 */

import com.subx.framework.IPlugin
import com.quantrix.core.api.QModelDocumentApplication
import javax.swing.JOptionPane

// The 'loader' variable is injected by the Groovy Loader Plugin.
// Use it to register menu items and other hooks.

return new IPlugin.Adapter() {

    String getId() { "com.example.hello-groovy" }

    void start() {
        println "[HelloPlugin] Started"

        // Register a menu item under Plugins → Hello → Say Hello...
        if (loader != null) {
            loader.registerMenuItem("Hello/Say Hello...", {
                // Count open models
                def app = QModelDocumentApplication.cFactory.getInstance()
                def docs = app?.openDocuments ?: []
                def names = docs.collect { it.name }.join(", ")

                JOptionPane.showMessageDialog(null,
                    "Hello from a Groovy plugin!\n\n" +
                    "Open models: ${docs.size()}\n" +
                    (names ? "Names: ${names}" : "(none)"),
                    "Hello Groovy Plugin",
                    JOptionPane.INFORMATION_MESSAGE)
            } as Runnable)
        }
    }

    void stop() {
        println "[HelloPlugin] Stopped"
    }
}
