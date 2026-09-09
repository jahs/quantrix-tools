package net.jahs.quantrix.groovyloader;

import com.subx.framework.IPlugin;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

public class GroovyLoaderPluginTest {
    private static final String ENTRY_POINT =
            "return net.jahs.quantrix.groovyloader.GroovyLoaderPluginTest.pluginFor(loader)";
    private static RecordingPlugin nextPlugin;
    private static int passed;
    private static int failed;

    public static IPlugin pluginFor(Object loader) {
        check(nextPlugin.loader == loader, "Entry point received the wrong loader");
        return nextPlugin;
    }

    public static void main(String[] args) throws Exception {
        for (boolean fromJar : new boolean[] {false, true}) {
            test("failed startup rolls back " + (fromJar ? "JAR" : "directory") + " plugin", () -> {
                try (Fixture fixture = new Fixture(fromJar)) {
                    fixture.plugin.startFailure = new IOException("startup failed");
                    fixture.load(ENTRY_POINT);
                    fixture.assertRolledBack("startup failed");
                    check(fixture.plugin.stops == 1, "Failed startup must call stop once");
                    check(fixture.plugin.stoppedBeforeClassLoaderClosed, "Stop needs the plugin classloader");
                    if (fromJar) {
                        check(fixture.plugin.sawExtractedLibrary, "Fixture must extract a nested JAR");
                        check(!fixture.plugin.extractedDirectory.exists(), "Extracted library directory leaked");
                    }
                }
            });
        }
        test("cleanup failure does not replace the startup failure", () -> {
            try (Fixture fixture = new Fixture(true)) {
                fixture.plugin.startFailure = new IOException("startup failed");
                fixture.plugin.stopFailure = new IOException("cleanup failed");
                fixture.load(ENTRY_POINT);
                fixture.assertRolledBack("startup failed");
                Throwable[] suppressed = fixture.plugin.startFailure.getSuppressed();
                check(suppressed.length == 1 && suppressed[0] == fixture.plugin.stopFailure,
                        "Cleanup failure must be attached to the original failure");
                check(!fixture.plugin.extractedDirectory.exists(), "Cleanup failure must not skip library cleanup");
            }
        });
        test("startup errors also roll back", () -> {
            try (Fixture fixture = new Fixture(false)) {
                fixture.plugin.startFailure = new AssertionError("startup assertion");
                fixture.load(ENTRY_POINT);
                fixture.assertRolledBack("startup assertion");
                check(fixture.plugin.stops == 1, "AssertionError must not skip stop");
            }
        });
        test("failed restart rolls back new and existing menu registrations", () -> {
            try (Fixture fixture = new Fixture(false)) {
                fixture.load(ENTRY_POINT);
                invoke(fixture.loader, "stopPlugin", new Class<?>[] {GroovyLoaderPlugin.PluginEntry.class}, fixture.entry());
                fixture.plugin.startFailure = new IOException("restart failed");
                invoke(fixture.loader, "startPlugin", new Class<?>[] {GroovyLoaderPlugin.PluginEntry.class}, fixture.entry());
                fixture.assertRolledBack("restart failed");
                check(fixture.plugin.starts == 2 && fixture.plugin.stops == 2, "Restart must have its own rollback");
            }
        });
        test("invalid entry point results release loading resources", () -> {
            try (Fixture fixture = new Fixture(true)) {
                fixture.load("loader.registerMenuItem('Fixture/Partial', {} as Runnable)\nreturn 42");
                fixture.assertRolledBack("Returned java.lang.Integer, expected IPlugin");
                check(fixture.plugin.starts == 0 && fixture.plugin.stops == 0, "No plugin was created");
            }
        });
        test("missing JAR entry point releases extracted libraries", () -> {
            try (Fixture fixture = new Fixture(true)) {
                fixture.load(null);
                fixture.assertRolledBack("No entry point in JAR: fixture.groovy");
            }
        });
        test("metadata failure after start also stops the plugin", () -> {
            try (Fixture fixture = new Fixture(false)) {
                fixture.plugin.idFailure = new IllegalStateException("ID failed");
                fixture.load(ENTRY_POINT);
                fixture.assertRolledBack("ID failed");
                check(fixture.plugin.stops == 1, "Started plugin must stop if initialization cannot finish");
            }
        });
        test("successful startup remains running until explicitly stopped", () -> {
            try (Fixture fixture = new Fixture(true)) {
                fixture.load(ENTRY_POINT);
                check("running".equals(fixture.entry().status), "Successful plugin should run");
                check(fixture.entry().plugin == fixture.plugin, "Running instance must be retained");
                check(fixture.plugin.stops == 0 && !fixture.plugin.sockets.get(0).isClosed(), "Premature rollback");
                fixture.loader.stop();
                check(fixture.plugin.stops == 1 && fixture.plugin.sockets.get(0).isClosed(), "Normal stop failed");
                check(!fixture.plugin.extractedDirectory.exists(), "Normal stop must remove libraries");
            }
        });
        test("reload recovers from failed startup with a fresh instance", () -> {
            try (Fixture fixture = new Fixture(false)) {
                fixture.plugin.startFailure = new IOException("startup failed");
                fixture.load(ENTRY_POINT);
                fixture.assertRolledBack("startup failed");
                fixture.useNewPlugin();
                check(fixture.loader.reloadById("fixture"), "Failed plugin must remain reloadable by directory");
                check("running".equals(fixture.entry().status), "Reload did not recover");
                check(fixture.entry().error == null, "Reload retained the previous failure");
                check(fixture.entry().plugin == fixture.plugin, "Reload must retain the fresh instance");
            }
        });
        System.out.println("\n" + passed + " loader tests passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    public static class RecordingPlugin extends IPlugin.Adapter {
        final GroovyLoaderPlugin loader;
        final List<ServerSocket> sockets = new ArrayList<>();
        Throwable startFailure;
        Throwable stopFailure;
        RuntimeException idFailure;
        int starts;
        int stops;
        File extractedDirectory;
        boolean sawExtractedLibrary;
        boolean stoppedBeforeClassLoaderClosed;

        RecordingPlugin(GroovyLoaderPlugin loader) { this.loader = loader; }

        @Override public String getId() {
            if (idFailure != null) throw idFailure;
            return "test.fixture";
        }

        @Override public void start() throws Exception {
            starts++;
            sockets.add(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
            loader.registerMenuItem("Fixture/Start " + starts, () -> {});
            extractedDirectory = entries(loader).get(0).tempLibDir;
            sawExtractedLibrary = extractedDirectory != null
                    && new File(extractedDirectory, "dependency.jar").isFile();
            throwIfPresent(startFailure);
        }

        @Override public void stop() throws Exception {
            stops++;
            stoppedBeforeClassLoaderClosed = entries(loader).get(0).classLoader != null;
            for (ServerSocket socket : sockets) socket.close();
            throwIfPresent(stopFailure);
        }

        private static void throwIfPresent(Throwable failure) throws Exception {
            if (failure instanceof Error) throw (Error) failure;
            if (failure != null) throw (Exception) failure;
        }
    }

    private static class Fixture implements AutoCloseable {
        final Path directory = Files.createTempDirectory("qx-loader-test-");
        final GroovyLoaderPlugin loader = new GroovyLoaderPlugin();
        final boolean fromJar;
        final Path source;
        final List<RecordingPlugin> plugins = new ArrayList<>();
        RecordingPlugin plugin;

        Fixture(boolean fromJar) throws Exception {
            this.fromJar = fromJar;
            source = directory.resolve(fromJar ? "fixture-1.0.jar" : "fixture");
            Field baseClassLoader = GroovyLoaderPlugin.class.getDeclaredField("baseClassLoader");
            baseClassLoader.setAccessible(true);
            baseClassLoader.set(loader, GroovyLoaderPluginTest.class.getClassLoader());
            loader.registerMenuItem("Other/Keep", () -> {});
            useNewPlugin();
        }

        void useNewPlugin() {
            plugin = new RecordingPlugin(loader);
            plugins.add(plugin);
            nextPlugin = plugin;
        }

        void load(String code) throws Exception {
            if (fromJar) {
                ByteArrayOutputStream libraryBytes = new ByteArrayOutputStream();
                try (JarOutputStream library = new JarOutputStream(libraryBytes)) {
                    library.putNextEntry(new JarEntry("marker.txt"));
                    library.closeEntry();
                }
                try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source))) {
                    jar.putNextEntry(new JarEntry("lib/dependency.jar"));
                    jar.write(libraryBytes.toByteArray());
                    jar.closeEntry();
                    if (code != null) {
                        jar.putNextEntry(new JarEntry("fixture.groovy"));
                        jar.write(code.getBytes(StandardCharsets.UTF_8));
                        jar.closeEntry();
                    }
                }
            } else {
                Files.createDirectories(source);
                Files.writeString(source.resolve("fixture.groovy"), code);
            }
            invoke(loader, "loadPlugin", new Class<?>[] {File.class, boolean.class}, source.toFile(), fromJar);
        }

        GroovyLoaderPlugin.PluginEntry entry() throws Exception { return entries(loader).get(0); }

        void assertRolledBack(String message) throws Exception {
            GroovyLoaderPlugin.PluginEntry entry = entry();
            check("failed".equals(entry.status), "Failure must remain visible in the manager");
            check(message.equals(entry.error), "Wrong failure: " + entry.error);
            check(entry.plugin == null, "Failed instance must be released");
            check(entry.classLoader == null, "Failed plugin classloader leaked");
            check(entry.tempLibDir == null, "Extracted libraries were not cleaned up");
            check(menuItems(loader).keySet().equals(Set.of("Other/Keep")), "Failed plugin menu items leaked");
            check(plugin.sockets.stream().allMatch(ServerSocket::isClosed), "Plugin socket leaked");
        }

        @Override public void close() throws Exception {
            try {
                loader.stop();
            } finally {
                // Also clean up after the deliberately failing red-phase tests.
                for (RecordingPlugin recorded : plugins) {
                    for (ServerSocket socket : recorded.sockets) socket.close();
                }
                try (Stream<Path> paths = Files.walk(directory)) {
                    for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                        Files.deleteIfExists(path);
                    }
                }
                nextPlugin = null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static List<GroovyLoaderPlugin.PluginEntry> entries(GroovyLoaderPlugin loader) throws Exception {
        return (List<GroovyLoaderPlugin.PluginEntry>) field(loader, "entries");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Runnable> menuItems(GroovyLoaderPlugin loader) throws Exception {
        return (Map<String, Runnable>) field(loader, "menuItems");
    }

    private static Object field(GroovyLoaderPlugin loader, String name) throws Exception {
        Field field = GroovyLoaderPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(loader);
    }

    private static void invoke(GroovyLoaderPlugin loader, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = GroovyLoaderPlugin.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(loader, args);
    }

    private interface TestBody { void run() throws Exception; }

    private static void test(String name, TestBody body) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { body.run(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            passed++;
            System.out.println("PASS: " + name);
        } catch (InvocationTargetException error) {
            failed++;
            System.err.println("FAIL: " + name);
            error.getCause().printStackTrace();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
