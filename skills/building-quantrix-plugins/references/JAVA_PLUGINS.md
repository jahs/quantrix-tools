# Java Plugin Development

Java plugins are compiled JARs installed in `~/Library/Application Support/Quantrix/plugins/`. They integrate with Quantrix via `plugin.xml` for menu actions and extension points.

Most plugins should be written in Groovy instead — see `GROOVY_PLUGINS.md`. Java is needed for the Groovy Loader Plugin itself, and for plugins that require `plugin.xml`-based extension registration.

## Java Version (CRITICAL)

Quantrix Modeler 24.4 runs on Java 11 (class file version 55.0). Plugin JARs MUST be compiled with `-source 11 -target 11`. Higher targets fail with `UnsupportedClassVersionError`.

## Plugin Structure

```
my-plugin/
├── src/main/java/com/example/MyPlugin.java
├── src/main/resources/
│   ├── plugin.xml          # Extension declarations
│   └── plugin.properties   # Localized strings
├── build.sh
└── VERSION
```

## Minimal Plugin

```java
package com.example.quantrix;

import com.subx.framework.IPlugin;
import com.subx.general.ui.api.actions.AbstractContextAction;
import com.subx.general.ui.api.actions.XActionEvent;
import com.subx.general.ui.api.actions.context.IActionContext;
import javax.swing.JOptionPane;

public class HelloPlugin extends IPlugin.Adapter {
    public static final String PLUGIN_ID = "com.example.quantrix.hello";

    @Override
    public String getId() { return PLUGIN_ID; }

    public static final class HelloAction extends AbstractContextAction {
        @Override
        public void performAction(IActionContext context, XActionEvent event) {
            JOptionPane.showMessageDialog(null,
                "Hello from a Java plugin!", "Hello", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
```

### plugin.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<plugin>
  <extension point="com.subx.framework.Plugin">
    <plugin class="com.example.quantrix.HelloPlugin" />
  </extension>

  <extension point="com.subx.general.ui.actions">
    <actionset name="Hello Actions">
      <action class="com.example.quantrix.HelloPlugin$HelloAction"
              id="com.example.quantrix.hello.action"
              name="%Hello_name" tooltip="%Hello_tooltip" />
    </actionset>
  </extension>

  <extension point="com.subx.general.ui.menu">
    <menu label="Plugins" location="quantrix-window/after Tools" name="PluginsMenu">
      <menu-item action-name="com.example.quantrix.hello.action"
                 location="last" type="regular" />
    </menu>
  </extension>
</plugin>
```

### plugin.properties

```properties
Hello_name=Hello...
Hello_tooltip=Say hello from a plugin
```

## Build

```bash
QX_APP="/Applications/Quantrix Modeler.app/Contents/java/app"
CP=$(find "$QX_APP" "$QX_APP/lib" -name "*.jar" | tr '\n' ':')

javac -source 11 -target 11 -cp "$CP" -d build/classes src/main/java/**/*.java
cp -r src/main/resources/* build/classes/
cd build/classes && jar cfm ../../dist/my-plugin.jar MANIFEST.MF .
```

See `groovy-loader-plugin/build.sh` for a complete working build script.

## Key Patterns

### IPlugin.Adapter

Always override `getId()`:
```java
@Override
public String getId() { return PLUGIN_ID; }
```

### Menu Actions

Use `AbstractContextAction`, not `AbstractSimpleAction`:
```java
public static class MyAction extends AbstractContextAction {
    @Override
    public void performAction(IActionContext context, XActionEvent event) {
        // ...
    }
}
```

### Generic Wildcard Return Types (CRITICAL)

Many Quantrix APIs return `List<? extends T>` not `List<T>`:

| Method | Returns |
|--------|---------|
| `QModel.getMatrices()` | `List<? extends QMatrix>` |
| `QMatrix.getCategories()` | `List<? extends QCategory>` |
| `QMatrix.getFormulae()` | `List<? extends QFormula>` |

### Naming

`setName(String)` from `IHasName.Mutable`. There is no `forceName()`.

### Undo Manager

```java
XUndoManager undo = doc.getUndoManager();
undo.perform("My Operation", () -> {
    // ... changes ...
    return null;
});
```

Methods: `getUndoName()`, `getRedoName()`, `canUndo()`, `canRedo()`, `undo()`, `redo()`, `begin(String)`, `commit()`, `rollback()`, `perform(String, IOperation)`.

### Modeler Role (Permissions)

Plugin code runs under the Default Role. Mutations need the Modeler session:
```java
XRole modelerRole = doc.getModelerRole();
XSession session = modelerRole.login(null);
session.performOperation(() -> {
    // ... mutations ...
    return null;
});
```

### Scripting API (sapi) for Structural Changes

Use `ModelImpl` for matrix create/delete — handles browser tree and undo:
```java
import com.quantrix.scripting.core.sapi.impl.ModelImpl;

ModelImpl scriptModel = new ModelImpl(doc);
scriptModel.getMatrices().create("Revenue");  // correct
// NOT: model.createMatrix()  — leaves ghost browser entries
```

### Embedded HTTP Server

`com.sun.net.httpserver.HttpServer` works inside plugins. Bind to loopback only:
```java
HttpServer server = HttpServer.create(
    new InetSocketAddress(InetAddress.getLoopbackAddress(), 8182), 0);
```

Compile with `--add-exports java.base/sun.net=ALL-UNNAMED`.

### UI Lifecycle Listener

Listen for document opens to add dynamic UI:
```java
DocumentUIApplication uiApp = DocumentUIApplication.runningInstance();
uiApp.addListener(new XDocumentApplicationUI.ListenerAdapter() {
    @Override
    public void uiApplicationDocumentOpened(XDocumentApplicationUI app, XDocumentFrame frame) {
        // Menu bar is available — add dynamic items
    }
});
```

## Locations

| Path | Purpose |
|------|---------|
| `~/Library/Application Support/Quantrix/plugins/` | Install dir |
| `~/Library/Application Support/Quantrix/logs/error.log` | Log output |
| `/Applications/Quantrix Modeler.app/Contents/java/app/lib/` | Quantrix JARs (classpath) |
| `/Applications/Quantrix Modeler.app/Contents/PlugIns/jre.bundle/` | Bundled JRE |

## Guidance

- Prefer Groovy plugins for most use cases
- Keep `plugin.xml` at the JAR root
- Action classes must be public with a no-arg constructor
- Check `error.log` after each change — Quantrix often fails silently
- Prefer `IPlugin.Adapter` over implementing `IPlugin` directly
