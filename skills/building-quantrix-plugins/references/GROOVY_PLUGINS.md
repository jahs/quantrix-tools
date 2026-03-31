# Groovy Plugin Development

Groovy plugins are directories (or JARs) loaded by the Groovy Loader Plugin. Each plugin gets its own classloader with access to all Quantrix APIs and JDK classes. Plugins are loaded with a code source URL under the Quantrix directory, so they receive full permissions from the security policy.

## Prerequisites

The **Groovy Loader Plugin** (`groovy-loader-plugin/`) must be installed as a JAR in `~/Library/Application Support/Quantrix/plugins/`. It's compiled once and doesn't change. See the repo's `groovy-loader-plugin/` directory for source and build script.

## Quick Start

1. Create `~/Library/Application Support/Quantrix/groovy-plugins/` (the loader creates it on first run)
2. Write a `.groovy` file that returns an `IPlugin` instance
3. Restart Quantrix (or use Plugins → Manage Groovy Plugins → Reload All)

## Minimal Example

```groovy
// hello-plugin.groovy — minimal Groovy plugin for Quantrix
import com.subx.framework.IPlugin
import javax.swing.JOptionPane

return new IPlugin.Adapter() {
    String getId() { "com.example.hello" }

    void start() {
        println "[HelloPlugin] Started"
        if (loader != null) {
            loader.registerMenuItem("Hello/Say Hello...", {
                JOptionPane.showMessageDialog(null,
                    "Hello from a Groovy plugin!",
                    "Hello Plugin", JOptionPane.INFORMATION_MESSAGE)
            } as Runnable)
        }
    }

    void stop() {
        println "[HelloPlugin] Stopped"
    }
}
```

See `example/groovy/hello-plugin.groovy` for the full example.

## Plugin Contract

A groovy plugin file must:

1. **Return an `IPlugin` instance** — use `new IPlugin.Adapter() { ... }` for convenience
2. **Implement `getId()`** — return a unique string ID
3. **Implement `start()`** — called when the plugin loads
4. **Implement `stop()`** — called when the plugin unloads (Reload, shutdown)

## Available Variables

The Groovy Loader injects these variables into the script scope:

| Variable | Type | Description |
|----------|------|-------------|
| `loader` | `GroovyLoaderPlugin` | The loader instance — use for registering menu items and hooks |

## Loader API

### `loader.registerMenuItem(String path, Runnable action)`

Register a menu item under the Plugins menu. Appears in all document windows.

Path supports `/` for sub-menus:

| Path | Result |
|------|--------|
| `"My Action..."` | Direct item under Plugins |
| `"Server/Log..."` | "Server" sub-menu → "Log..." item |
| `"Tools/Import//Export..."` | "Tools" sub-menu → "Import/Export..." item (`//` = literal `/`) |

Items are automatically added to new document windows as they open.

## Classloader

Groovy plugins run with a composite classloader that sees:

1. **All Quantrix classes** — engine, core, UI, scripting (same as Java plugins)
2. **All JDK modules** — including `com.sun.net.httpserver.HttpServer`
3. **All Groovy modules** — `groovy-json`, `groovy-xml`, etc.
4. **Full permissions** — plugins are loaded with a code source URL under the Quantrix directory, matching the security policy. They can bind sockets, read system properties, do file I/O. The trust boundary is which plugins are installed.

This means you can:
- Import and use any Quantrix API class
- Create HTTP servers (`com.sun.net.httpserver.HttpServer`)
- Read system properties, access the file system, use reflection
- Use Groovy JSON (`groovy.json.JsonSlurper`, `groovy.json.JsonOutput`)

## Accessing Quantrix APIs

### Application and Documents

```groovy
import com.quantrix.core.api.QModelDocumentApplication
import com.quantrix.core.api.QModelDocument

def app = QModelDocumentApplication.cFactory.getInstance()
def docs = app.openDocuments
def doc = docs.find { it.name == "Balance Sheet.model" }
def model = doc.model
```

### Scripting API (sapi) — Preferred for Structural Changes

```groovy
import com.quantrix.scripting.core.sapi.impl.ModelImpl

def scriptModel = new ModelImpl(doc)
def matrices = scriptModel.matrices

// Create matrix
matrices.create("Revenue")

// Delete matrix
matrices.getAt("Revenue").delete()

// Categories and items
def m = matrices.getAt("Revenue")
m.categories               // list categories
m.formulae                 // list formulas
m.formulae[0].text = "Total = sum(summary(Items))"
```

### UI Context (current view, selection)

```groovy
import com.subx.document.ui.iapi.DocumentUIApplication

def uiApp = DocumentUIApplication.runningInstance()
def frame = uiApp.getUIForDocument(doc, false)
def ctx = frame.actionContext
def sel = ctx.getInterface(com.subx.general.ui.api.selection.IHasSelection)?.selection
println sel?.description  // e.g. "Cash:2020"
```

### Groovy Scripting Context (eval with pipe syntax)

```groovy
import com.subx.scripting.core.api.XGroovyFactory

def context = XGroovyFactory.cInstance.getGroovyContext(frame)
def scriptClass = XGroovyFactory.cInstance.compileScript(context, 'matrices.collect { it.name }')
def script = scriptClass.getDeclaredConstructor().newInstance()
context.createBindingOn(script)
def result = frame.perform("Eval", "Eval", { script.run() })
```

### Permissions (Modeler Role)

Plugin code runs under the Default Role. For mutations, use a Modeler session:

```groovy
import com.subx.general.core.api.role.XRole

XRole modelerRole = doc.modelerRole
def session = modelerRole.login(null)
session.performOperation {
    // ... mutations here ...
    return null
}
```

## HTTP Server Example

See `groovy-server-plugin/quantrix-server.groovy` in the repo for a complete HTTP server with `/eval` endpoint. Key pattern:

```groovy
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange

def server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 8182), 0)
server.executor = java.util.concurrent.Executors.newFixedThreadPool(4)

// Closures must be captured for use inside anonymous inner classes
def myHandler = { HttpExchange ex -> /* handle request */ } as Closure

server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
    void handle(HttpExchange ex) { myHandler.call(ex) }
})
server.start()
```

**Note:** Groovy closures cannot be used directly as `HttpHandler` SAM types due to classloader boundaries. Use an explicit anonymous class that calls the captured closure.

## Development Workflow

1. Edit the `.groovy` file
2. Open Plugins → Manage Groovy Plugins
3. Click **Reload All** (or select the plugin and click **Reload**)
4. Test — no Quantrix restart needed

For debugging, use `println` — output goes to `~/Library/Application Support/Quantrix/logs/error.log` and the Quantrix Output Console (Ctrl+Shift+O).

## Pitfalls

1. **Closure scope in anonymous inner classes** — script-level functions/closures are not directly accessible from `new IPlugin.Adapter() { ... }`. Capture them as variables before the `return`.

2. **EDT requirement** — all Quantrix UI and API calls must run on the Swing EDT. Use `SwingUtilities.invokeLater { ... }` or `SwingUtilities.invokeAndWait { ... }` from background threads (e.g. HTTP handlers).

3. **Groovy property syntax** — `InetAddress.loopbackAddress` doesn't work for static methods. Use `InetAddress.getLoopbackAddress()`.

4. **Use sapi for matrix create/delete** — direct `model.createMatrix()` / `model.removeMatrix()` leaves ghost browser entries. Use `ModelImpl(doc).matrices.create(name)` / `.delete()`.
