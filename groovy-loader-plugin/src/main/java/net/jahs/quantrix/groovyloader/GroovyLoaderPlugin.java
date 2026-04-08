package net.jahs.quantrix.groovyloader;

import com.subx.document.ui.api.XDocumentApplicationUI;
import com.subx.document.ui.api.XDocumentFrame;
import com.subx.document.ui.iapi.DocumentUIApplication;
import com.subx.framework.IPlugin;
import com.subx.general.ui.api.actions.AbstractContextAction;
import com.subx.general.ui.api.actions.XActionEvent;
import com.subx.general.ui.api.actions.context.IActionContext;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovyShell;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads groovy plugins from directories and runs them as Quantrix plugins.
 *
 * Each plugin is a directory (or JAR) under the groovy-plugins folder containing:
 * <ul>
 *   <li>{@code <name>.groovy} — entry point script (must return an IPlugin)</li>
 *   <li>Additional .groovy files — compiled as helper classes before the entry point runs</li>
 *   <li>Optional .jar files — added to the plugin's classloader</li>
 * </ul>
 *
 * Each plugin gets its own {@link GroovyClassLoader} for isolation and clean
 * reloading. On reload, the old classloader is discarded and a fresh one is
 * created, so class redefinition is never an issue.
 *
 * Groovy scripts receive a {@code loader} variable for registering hooks:
 * <pre>loader.registerMenuItem("My Action...", { doSomething() })</pre>
 *
 * Provides Plugins &rarr; Manage Groovy Plugins... for status, reload, start/stop.
 *
 * <h3>Security levels</h3>
 * Quantrix has three execution privilege levels:
 * <ol>
 *   <li><b>Sandboxed</b> — The scripting console and XGroovyFactory. Has model,
 *       matrices, pipe syntax, undo wrapping. Cannot access framework classes,
 *       JDK internals, or the OS. This is what user/agent scripts should use.</li>
 *   <li><b>Plugin</b> — Groovy scripts loaded with a code source URL pointing
 *       into the groovy-plugins directory (or a JAR within it). Quantrix's
 *       security policy grants these the same permissions as directory-loaded
 *       scripts — full access to Quantrix APIs, JDK, sockets, system
 *       properties, and file I/O.</li>
 *   <li><b>Privileged</b> — Code with AllPermission (this loader's own jar).</li>
 * </ol>
 */
public class GroovyLoaderPlugin extends IPlugin.Adapter {

    public static final String PLUGIN_ID = "net.jahs.quantrix.groovyloader";
    private static final String DIR_PROPERTY = "quantrix.groovy.plugins";
    private static final String DEFAULT_DIR = System.getProperty("user.home")
            + "/Library/Application Support/Quantrix/groovy-plugins";
    private static final String PLUGINS_MENU_LABEL = "Plugins";

    private static GroovyLoaderPlugin instance;

    // ── Per-plugin state ────────────────────────────────────────────────

    static class PluginEntry {
        final File source;       // directory or .jar file
        final String pluginName; // derived name (directory name or jar stem)
        final boolean fromJar;
        String pluginId;
        String version;
        String status;  // "running", "failed", "stopped"
        String error;
        IPlugin plugin;
        GroovyClassLoader classLoader;
        File tempLibDir; // temp dir for extracted nested JARs (JAR plugins only)

        PluginEntry(File source, boolean fromJar) {
            this.source = source;
            this.fromJar = fromJar;
            this.status = "loading";
            if (fromJar) {
                // quantrix-server-0.2.0.jar → quantrix-server
                // quantrix-server.jar → quantrix-server
                String name = source.getName().replaceFirst("\\.jar$", "");
                name = name.replaceFirst("-\\d+\\.\\d+.*$", "");
                this.pluginName = name;
            } else {
                this.pluginName = source.getName();
            }
            // version is set later via reflection in initPlugin()
        }
    }

    private final List<PluginEntry> entries = new ArrayList<>();
    private final Map<String, Runnable> menuItems = new LinkedHashMap<>();
    private final Map<String, String> menuItemOwners = new HashMap<>(); // label → pluginName
    private final Set<Integer> populatedMenuBars = new HashSet<>();
    private final Map<Integer, Integer> nativeMenuItemCounts = new HashMap<>();
    private String currentlyLoadingPlugin; // set during loadPluginEntry()
    private ClassLoader baseClassLoader;
    private XDocumentApplicationUI.Listener uiListener;

    @Override
    public String getId() { return PLUGIN_ID; }

    @Override
    public void start() {
        instance = this;
        initBaseClassLoader();
        loadAll();
        wirePluginsMenu();
    }

    @Override
    public void stop() {
        stopAll();
        if (uiListener != null) {
            try {
                DocumentUIApplication uiApp = DocumentUIApplication.runningInstance();
                if (uiApp != null) uiApp.removeListener(uiListener);
            } catch (Exception ignored) {}
            uiListener = null;
        }
        populatedMenuBars.clear();
        instance = null;
    }

    // ── Public API for groovy plugins ───────────────────────────────────

    /**
     * Register a menu item under the Plugins menu.
     * Items appear in all document windows (current and future).
     */
    public void registerMenuItem(String label, Runnable action) {
        menuItems.put(label, action);
        if (currentlyLoadingPlugin != null) {
            menuItemOwners.put(label, currentlyLoadingPlugin);
        }
        log("Registered menu item: " + label);
        SwingUtilities.invokeLater(this::populateAllMenuBars);
    }

    /**
     * List all loaded groovy plugins with their status.
     * Returns a list of maps with: id, directory, version, status, error.
     */
    public List<Map<String, Object>> listPlugins() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PluginEntry e : entries) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.pluginId != null ? e.pluginId : e.pluginName);
            m.put("directory", e.pluginName);
            if (e.version != null) m.put("version", e.version);
            m.put("status", e.status);
            if (e.error != null) m.put("error", e.error);
            result.add(m);
        }
        return result;
    }

    /**
     * Reload a single plugin by its ID or directory name.
     * Returns true if found and reloaded.
     */
    public boolean reloadById(String id) {
        for (PluginEntry e : entries) {
            if (id.equals(e.pluginId) || id.equals(e.pluginName)) {
                reloadPlugin(e);
                SwingUtilities.invokeLater(this::populateAllMenuBars);
                return true;
            }
        }
        return false;
    }

    // ── Base classloader setup ──────────────────────────────────────────

    private void initBaseClassLoader() {
        ClassLoader pluginCL = this.getClass().getClassLoader();
        ClassLoader systemCL = ClassLoader.getSystemClassLoader();
        ClassLoader compositeCL = new CompositeClassLoader(pluginCL, systemCL);
        baseClassLoader = buildClassLoader(compositeCL);
    }

    // ── Load / Stop / Reload ────────────────────────────────────────────

    private void loadAll() {
        File dir = resolveDir();
        log("Groovy plugin directory: " + dir.getAbsolutePath());

        if (!dir.isDirectory()) {
            log("Directory does not exist, creating it.");
            dir.mkdirs();
            return;
        }

        // Collect directory-based plugins
        Set<String> dirNames = new HashSet<>();
        File[] dirs = dir.listFiles(File::isDirectory);
        if (dirs != null) {
            Arrays.sort(dirs);
            for (File pluginDir : dirs) {
                File entryPoint = new File(pluginDir, pluginDir.getName() + ".groovy");
                if (entryPoint.isFile()) {
                    dirNames.add(pluginDir.getName());
                    loadPlugin(pluginDir, false);
                } else {
                    log("Skipped: " + pluginDir.getName() + " (no " + pluginDir.getName() + ".groovy)");
                }
            }
        }

        // Collect JAR-based plugins (directory wins if both exist)
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars != null) {
            Arrays.sort(jars);
            for (File jarFile : jars) {
                String stem = jarFile.getName().replaceFirst("\\.jar$", "");
                stem = stem.replaceFirst("-\\d+\\.\\d+.*$", "");
                if (dirNames.contains(stem)) {
                    log("Skipped JAR: " + jarFile.getName() + " (directory " + stem + "/ takes precedence)");
                } else {
                    loadPlugin(jarFile, true);
                }
            }
        }

        if (entries.isEmpty()) {
            log("No plugins found.");
        } else {
            int running = (int) entries.stream().filter(e -> "running".equals(e.status)).count();
            log(running + " of " + entries.size() + " groovy plugin(s) loaded.");
        }
    }

    private void loadPlugin(File source, boolean fromJar) {
        PluginEntry entry = new PluginEntry(source, fromJar);
        entries.add(entry);
        loadPluginEntry(entry);
    }

    private void loadPluginEntry(PluginEntry entry) {
        currentlyLoadingPlugin = entry.pluginName;
        try {
            if (entry.fromJar) {
                loadFromJar(entry);
            } else {
                loadFromDirectory(entry);
            }
        } catch (Exception e) {
            entry.status = "failed";
            entry.error = e.getMessage();
            log("Failed: " + entry.pluginName + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            currentlyLoadingPlugin = null;
        }
    }

    private void loadFromDirectory(PluginEntry entry) throws Exception {
        String entryPointName = entry.pluginName + ".groovy";
        File entryPoint = new File(entry.source, entryPointName);

        if (!entryPoint.isFile()) {
            entry.status = "failed";
            entry.error = "No entry point: " + entryPointName;
            log("Skipped: " + entry.pluginName + " (no entry point)");
            return;
        }

        log("Loading: " + entry.pluginName + " (directory)");

        GroovyClassLoader gcl = new GroovyClassLoader(baseClassLoader);
        entry.classLoader = gcl;

        // Add *.jar files to classloader
        File[] jars = entry.source.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars != null) {
            Arrays.sort(jars);
            for (File jar : jars) {
                try {
                    gcl.addURL(jar.toURI().toURL());
                    log("  Added: " + jar.getName());
                } catch (Exception ex) {
                    log("  Could not add jar: " + jar.getName() + ": " + ex.getMessage());
                }
            }
        }

        // Compile helper .groovy files
        File[] helpers = entry.source.listFiles((d, name) ->
            name.endsWith(".groovy") && !name.equals(entryPointName));
        if (helpers != null) {
            Arrays.sort(helpers);
            for (File f : helpers) {
                log("  Compiling: " + f.getName());
                gcl.parseClass(f);
            }
        }

        // Evaluate entry point
        GroovyShell shell = new GroovyShell(gcl);
        shell.setVariable("loader", this);
        initPlugin(entry, shell.evaluate(entryPoint));
    }

    private void loadFromJar(PluginEntry entry) throws Exception {
        String entryPointName = entry.pluginName + ".groovy";

        log("Loading: " + entry.pluginName + " (JAR: " + entry.source.getName() + ")");

        GroovyClassLoader gcl = new GroovyClassLoader(baseClassLoader);
        entry.classLoader = gcl;

        // Add the plugin JAR itself to the classloader
        gcl.addURL(entry.source.toURI().toURL());

        // Extract nested .jar and compile helper .groovy from inside the plugin JAR
        try (JarFile jar = new JarFile(entry.source)) {
            // Extract nested .jar entries to temp files (URLClassLoader can't
            // read JARs nested inside JARs)
            File tempDir = java.nio.file.Files.createTempDirectory("qx-plugin-lib-").toFile();
            entry.tempLibDir = tempDir;
            Enumeration<JarEntry> jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry je = jarEntries.nextElement();
                if (je.getName().endsWith(".jar") && !je.isDirectory()) {
                    String shortName = je.getName().contains("/")
                            ? je.getName().substring(je.getName().lastIndexOf('/') + 1)
                            : je.getName();
                    File tempJar = new File(tempDir, shortName);
                    try (InputStream is = jar.getInputStream(je)) {
                        java.nio.file.Files.copy(is, tempJar.toPath());
                    }
                    gcl.addURL(tempJar.toURI().toURL());
                    log("  Added: " + shortName);
                }
            }

            // Compile helper .groovy files
            List<String> helperNames = new ArrayList<>();
            jarEntries = jar.entries();
            while (jarEntries.hasMoreElements()) {
                JarEntry je = jarEntries.nextElement();
                String name = je.getName();
                if (name.endsWith(".groovy") && !je.isDirectory() && !name.equals(entryPointName)) {
                    helperNames.add(name);
                }
            }
            // Build jar: URL base for code source attribution — classes compiled
            // from these URLs get the JAR file's code source, which is under the
            // Quantrix groovy-plugins directory and thus matches the security policy.
            String jarUrlBase = "jar:" + entry.source.toURI() + "!/";

            java.util.Collections.sort(helperNames);
            for (String name : helperNames) {
                log("  Compiling: " + name);
                URL helperUrl = new URL(jarUrlBase + name);
                gcl.parseClass(new GroovyCodeSource(helperUrl));
            }

            // Evaluate entry point
            JarEntry epEntry = jar.getJarEntry(entryPointName);
            if (epEntry == null) {
                entry.status = "failed";
                entry.error = "No entry point in JAR: " + entryPointName;
                log("Skipped: " + entry.pluginName + " (no " + entryPointName + " in JAR)");
                return;
            }

            URL entryPointUrl = new URL(jarUrlBase + entryPointName);
            GroovyShell shell = new GroovyShell(gcl);
            shell.setVariable("loader", this);
            initPlugin(entry, shell.evaluate(new GroovyCodeSource(entryPointUrl)));

            // Groovy-compiled classes don't inherit JAR manifest attributes,
            // so read the version from the manifest directly
            if (entry.version == null || "dev".equals(entry.version)) {
                java.util.jar.Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    String implVersion = manifest.getMainAttributes()
                            .getValue("Implementation-Version");
                    if (implVersion != null && !implVersion.isEmpty()) {
                        entry.version = implVersion;
                    }
                }
            }
        }
    }

    private void initPlugin(PluginEntry entry, Object result) throws Exception {
        if (result instanceof IPlugin) {
            IPlugin plugin = (IPlugin) result;
            plugin.start();
            entry.plugin = plugin;
            entry.pluginId = plugin.getId();
            entry.status = "running";
            // Read version via reflection if the plugin exposes getVersion()
            try {
                entry.version = (String) plugin.getClass().getMethod("getVersion").invoke(plugin);
            } catch (ReflectiveOperationException e) {
                // plugin doesn't report version — that's fine
            }
            log("Started: " + entry.pluginName + " [" + plugin.getId() + "]"
                    + (entry.version != null ? " v" + entry.version : ""));
        } else {
            entry.status = "failed";
            entry.error = "Returned " + (result == null ? "null" : result.getClass().getName())
                    + ", expected IPlugin";
            log("Skipped: " + entry.pluginName + " (" + entry.error + ")");
        }
    }

    private void stopPlugin(PluginEntry entry) {
        if (entry.plugin != null && "running".equals(entry.status)) {
            try {
                entry.plugin.stop();
                entry.status = "stopped";
                log("Stopped: " + entry.pluginId);
            } catch (Exception e) {
                entry.status = "failed";
                entry.error = "Stop failed: " + e.getMessage();
                log("Error stopping " + entry.pluginId + ": " + e.getMessage());
            }
        }
    }

    private void startPlugin(PluginEntry entry) {
        if (entry.plugin != null && !"running".equals(entry.status)) {
            try {
                entry.plugin.start();
                entry.status = "running";
                entry.error = null;
                log("Started: " + entry.pluginId);
            } catch (Exception e) {
                entry.status = "failed";
                entry.error = "Start failed: " + e.getMessage();
                log("Error starting " + entry.pluginId + ": " + e.getMessage());
            }
        }
    }

    private void reloadPlugin(PluginEntry entry) {
        stopPlugin(entry);
        removePluginMenuItems(entry.pluginName);
        closeClassLoader(entry);

        int idx = entries.indexOf(entry);
        entries.remove(entry);

        PluginEntry newEntry = new PluginEntry(entry.source, entry.fromJar);
        if (idx >= 0) {
            entries.add(idx, newEntry);
        } else {
            entries.add(newEntry);
        }

        loadPluginEntry(newEntry);
    }

    private void removePluginMenuItems(String pluginName) {
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, String> e : menuItemOwners.entrySet()) {
            if (pluginName.equals(e.getValue())) {
                toRemove.add(e.getKey());
            }
        }
        for (String label : toRemove) {
            menuItems.remove(label);
            menuItemOwners.remove(label);
        }
        if (!toRemove.isEmpty()) {
            // Rebuild menus from scratch to remove stale entries
            clearDynamicMenuItems();
            SwingUtilities.invokeLater(this::populateAllMenuBars);
        }
    }

    private void closeClassLoader(PluginEntry entry) {
        if (entry.classLoader != null) {
            try {
                entry.classLoader.close();
            } catch (IOException e) {
                log("Error closing classloader for " + entry.pluginName + ": " + e.getMessage());
            }
            entry.classLoader = null;
        }
        // Clean up extracted nested JARs from JAR-based plugins
        if (entry.tempLibDir != null && entry.tempLibDir.isDirectory()) {
            File[] files = entry.tempLibDir.listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            entry.tempLibDir.delete();
            entry.tempLibDir = null;
        }
    }

    private void stopAll() {
        for (int i = entries.size() - 1; i >= 0; i--) {
            PluginEntry entry = entries.get(i);
            stopPlugin(entry);
            closeClassLoader(entry);
        }
        entries.clear();
        clearDynamicMenuItems(true);
        menuItems.clear();
        menuItemOwners.clear();
    }

    void reloadAll() {
        log("Reloading all groovy plugins...");
        stopAll();
        initBaseClassLoader();
        loadAll();
        SwingUtilities.invokeLater(this::populateAllMenuBars);
        log("Reload complete.");
    }

    // ── Plugins menu integration ────────────────────────────────────────

    private void wirePluginsMenu() {
        SwingUtilities.invokeLater(() -> {
            populateAllMenuBars();
            try {
                DocumentUIApplication uiApp = DocumentUIApplication.runningInstance();
                if (uiApp != null) {
                    uiApp.addListener(uiListener = new XDocumentApplicationUI.ListenerAdapter() {
                        @Override
                        public void uiApplicationDocumentOpened(XDocumentApplicationUI app,
                                                                 XDocumentFrame frame) {
                            SwingUtilities.invokeLater(() -> populateMenuBar(frame.getFrame()));
                        }
                    });
                }
            } catch (Exception e) {
                log("Could not register UI listener: " + e.getMessage());
            }
        });
    }

    private void populateAllMenuBars() {
        for (Frame frame : Frame.getFrames()) {
            if (frame instanceof JFrame && frame.isVisible()) {
                populateMenuBar((JFrame) frame);
            }
        }
    }

    private void populateMenuBar(JFrame frame) {
        if (frame == null) return;
        JMenuBar menuBar = frame.getJMenuBar();
        if (menuBar == null) return;

        int barId = System.identityHashCode(menuBar);
        JMenu pluginsMenu = findPluginsMenu(menuBar);
        if (pluginsMenu == null) return;

        if (!populatedMenuBars.contains(barId)) {
            if (!nativeMenuItemCounts.containsKey(barId)) {
                nativeMenuItemCounts.put(barId, pluginsMenu.getItemCount());
            }
            populatedMenuBars.add(barId);
            if (pluginsMenu.getItemCount() > 0 && !menuItems.isEmpty()) {
                pluginsMenu.addSeparator();
            }
        }

        for (Map.Entry<String, Runnable> item : menuItems.entrySet()) {
            addMenuItemIfMissing(pluginsMenu, item.getKey(), item.getValue());
        }
    }

    private JMenu findPluginsMenu(JMenuBar menuBar) {
        for (int i = 0; i < menuBar.getMenuCount(); i++) {
            JMenu menu = menuBar.getMenu(i);
            if (menu != null && PLUGINS_MENU_LABEL.equalsIgnoreCase(menu.getText())) {
                return menu;
            }
        }
        return null;
    }

    /**
     * Add a menu item, supporting "/" paths for sub-menus.
     * "Server/Log..." creates a "Server" sub-menu with "Log..." in it.
     * Use "//" for a literal "/" in a name (e.g. "Input//Output" → "Input/Output").
     */
    private void addMenuItemIfMissing(JMenu menu, String path, Runnable action) {
        int slash = -1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                if (i + 1 < path.length() && path.charAt(i + 1) == '/') {
                    i++;
                } else {
                    slash = i;
                    break;
                }
            }
        }

        if (slash > 0) {
            String subMenuName = path.substring(0, slash).replace("//", "/");
            String remainder = path.substring(slash + 1);
            JMenu subMenu = findOrCreateSubMenu(menu, subMenuName);
            addMenuItemIfMissing(subMenu, remainder, action);
            return;
        }

        String label = path.replace("//", "/");
        for (int i = 0; i < menu.getItemCount(); i++) {
            JMenuItem existing = menu.getItem(i);
            if (existing != null && label.equals(existing.getText())) return;
        }
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }

    private JMenu findOrCreateSubMenu(JMenu parent, String name) {
        for (int i = 0; i < parent.getItemCount(); i++) {
            JMenuItem item = parent.getItem(i);
            if (item instanceof JMenu && name.equals(item.getText())) {
                return (JMenu) item;
            }
        }
        JMenu sub = new JMenu(name);
        parent.add(sub);
        return sub;
    }

    private void clearDynamicMenuItems() {
        clearDynamicMenuItems(false);
    }

    private void clearDynamicMenuItems(boolean resetNativeCounts) {
        SwingUtilities.invokeLater(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (!(frame instanceof JFrame)) continue;
                JMenuBar menuBar = ((JFrame) frame).getJMenuBar();
                if (menuBar == null) continue;
                JMenu pluginsMenu = findPluginsMenu(menuBar);
                if (pluginsMenu == null) continue;
                int barId = System.identityHashCode(menuBar);
                int nativeCount = nativeMenuItemCounts.getOrDefault(barId, pluginsMenu.getItemCount());
                while (pluginsMenu.getItemCount() > nativeCount) {
                    pluginsMenu.remove(pluginsMenu.getItemCount() - 1);
                }
            }
            populatedMenuBars.clear();
            if (resetNativeCounts) {
                nativeMenuItemCounts.clear();
            }
        });
    }

    // ── Manager dialog ──────────────────────────────────────────────────

    void showManagerDialog() {
        JDialog dialog = new JDialog((Frame) null, "Manage Groovy Plugins", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Table
        String[] columns = {"Plugin", "Version", "ID", "Status", "Error"};
        AbstractTableModel tableModel = new AbstractTableModel() {
            @Override public int getRowCount() { return entries.size(); }
            @Override public int getColumnCount() { return columns.length; }
            @Override public String getColumnName(int col) { return columns[col]; }
            @Override
            public Object getValueAt(int row, int col) {
                PluginEntry e = entries.get(row);
                switch (col) {
                    case 0: return e.pluginName + (e.fromJar ? " (jar)" : "");
                    case 1: return e.version != null ? e.version : "-";
                    case 2: return e.pluginId != null ? e.pluginId : "-";
                    case 3: return e.status;
                    case 4: return e.error != null ? e.error : "";
                    default: return "";
                }
            }
        };
        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(160);
        table.getColumnModel().getColumn(1).setPreferredWidth(60);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(270);

        // Color status cells
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel) {
                    String status = String.valueOf(value);
                    if ("running".equals(status)) c.setForeground(new Color(0, 128, 0));
                    else if ("failed".equals(status)) c.setForeground(Color.RED);
                    else if ("stopped".equals(status)) c.setForeground(Color.GRAY);
                    else c.setForeground(Color.BLACK);
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(760, 200));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Directory label
        File dir = resolveDir();
        JLabel dirLabel = new JLabel("Directory: " + dir.getAbsolutePath());
        dirLabel.setFont(dirLabel.getFont().deriveFont(Font.PLAIN, 11f));
        panel.add(dirLabel, BorderLayout.NORTH);

        // Buttons
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

        JButton startBtn = new JButton("Start");
        startBtn.setEnabled(false);
        startBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                startPlugin(entries.get(row));
                tableModel.fireTableDataChanged();
            }
        });
        buttons.add(startBtn);

        JButton stopBtn = new JButton("Stop");
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                stopPlugin(entries.get(row));
                tableModel.fireTableDataChanged();
            }
        });
        buttons.add(stopBtn);

        JButton reloadOneBtn = new JButton("Reload");
        reloadOneBtn.setEnabled(false);
        reloadOneBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                reloadPlugin(entries.get(row));
                tableModel.fireTableDataChanged();
                SwingUtilities.invokeLater(GroovyLoaderPlugin.this::populateAllMenuBars);
            }
        });
        buttons.add(reloadOneBtn);

        buttons.add(Box.createHorizontalStrut(12));

        JButton openDirBtn = new JButton("Open Directory");
        openDirBtn.addActionListener(e -> {
            try { Desktop.getDesktop().open(dir); }
            catch (Exception ex) { log("Could not open directory: " + ex.getMessage()); }
        });
        buttons.add(openDirBtn);

        JButton reloadAllBtn = new JButton("Reload All");
        reloadAllBtn.addActionListener(e -> {
            reloadAll();
            tableModel.fireTableDataChanged();
        });
        buttons.add(reloadAllBtn);

        JButton closeBtn = new JButton("Close");
        closeBtn.addActionListener(e -> dialog.dispose());
        buttons.add(closeBtn);

        // Enable/disable per-plugin buttons based on selection
        table.getSelectionModel().addListSelectionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0 && row < entries.size()) {
                PluginEntry entry = entries.get(row);
                startBtn.setEnabled(!"running".equals(entry.status) && entry.plugin != null);
                stopBtn.setEnabled("running".equals(entry.status));
                reloadOneBtn.setEnabled(true);
            } else {
                startBtn.setEnabled(false);
                stopBtn.setEnabled(false);
                reloadOneBtn.setEnabled(false);
            }
        });

        panel.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    // ── Menu action ─────────────────────────────────────────────────────

    public static class ManagePluginsAction extends AbstractContextAction {
        @Override
        public void performAction(IActionContext context, XActionEvent event) {
            if (instance != null) {
                instance.showManagerDialog();
            }
        }
    }

    // ── Classloader helpers ─────────────────────────────────────────────

    private ClassLoader buildClassLoader(ClassLoader parent) {
        String qxApp = System.getProperty("user.dir");
        File appDir = new File(qxApp);
        if (!appDir.isDirectory()) {
            appDir = new File("/Applications/Quantrix Modeler.app/Contents/java/app");
        }

        List<URL> urls = new ArrayList<>();
        addJars(urls, appDir);
        addJars(urls, new File(appDir, "lib"));
        addJars(urls, new File(System.getProperty("user.home")
            + "/Library/Application Support/Quantrix/plugins"));

        if (urls.isEmpty()) {
            log("No jars found, using parent classloader only.");
            return parent;
        }

        log("Extended classloader with " + urls.size() + " jars");
        return new URLClassLoader(urls.toArray(new URL[0]), parent);
    }

    private void addJars(List<URL> urls, File dir) {
        if (!dir.isDirectory()) return;
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars != null) {
            for (File jar : jars) {
                try { urls.add(jar.toURI().toURL()); }
                catch (Exception e) { /* skip */ }
            }
        }
    }

    private File resolveDir() {
        String path = System.getProperty(DIR_PROPERTY);
        return new File(path != null ? path : DEFAULT_DIR);
    }

    private static void log(String msg) {
        System.out.println("[GroovyLoader] " + msg);
    }

    /**
     * Classloader that delegates to primary first, then fallback.
     * Lets Groovy see both Quantrix classes and JDK module exports.
     */
    private static class CompositeClassLoader extends ClassLoader {
        private final ClassLoader fallback;

        CompositeClassLoader(ClassLoader primary, ClassLoader fallback) {
            super(primary);
            this.fallback = fallback;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            return fallback.loadClass(name);
        }
    }
}
