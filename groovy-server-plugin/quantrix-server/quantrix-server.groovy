/*
 * Quantrix HTTP Server Plugin
 *
 * Loaded by the Groovy Loader Plugin. Runs a loopback HTTP server for
 * model scripting and plugin management.
 *
 * Endpoints:
 *   GET  /status
 *   GET  /models
 *   POST /models/{id}/script          — sandboxed, undo-wrapped QGroovy
 *   POST /system/script-unsafe        — raw Groovy, no sandbox or undo
 *   GET  /loader/plugins
 *   POST /loader/plugins/{id}/reload
 *   POST /loader/reload
 *
 * Script requests accept text/x-groovy or application/json {"script": "..."}.
 * All requests require Authorization: Bearer <token>. The token is written
 * to ~/Library/Application Support/Quantrix/.server-token with mode 0600.
 */

import com.quantrix.core.api.QModelDocument
import com.quantrix.core.api.QModelDocumentApplication
import com.subx.document.ui.iapi.DocumentUIApplication
import com.subx.framework.IPlugin
import com.subx.scripting.core.api.XGroovyFactory
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.jahs.quantrix.preprocessor.SelectionPreprocessor

import java.awt.*
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.List
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.swing.*

class QuantrixServerPlugin extends IPlugin.Adapter {
    private static final Pattern MODEL_SCRIPT_ROUTE = Pattern.compile('^/models/([^/]+)/script$')
    private static final Pattern PLUGIN_RELOAD_ROUTE = Pattern.compile('^/loader/plugins/([^/]+)/reload$')
    private static final DateTimeFormatter REQUEST_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def loader
    int listenPort = 8182
    Path authTokenPath = Path.of(System.getProperty("user.home"),
        "Library/Application Support/Quantrix/.server-token")

    private HttpServer httpServer
    private ExecutorService requestExecutor
    private String authToken

    // Log history and Swing widgets are confined to the EDT.
    private final List<String> logLines = []
    private JTextArea logTextArea
    private JDialog logDialog

    @Override
    String getId() { "net.jahs.quantrix.server" }

    String getVersion() { getClass().package?.implementationVersion ?: "dev" }

    int getBoundPort() { httpServer.address.port }

    // Lifecycle

    @Override
    void start() {
        def token = UUID.randomUUID().toString()
        writePrivateFileAtomically(authTokenPath, token)
        authToken = token
        log("Auth token written to ${authTokenPath}")

        def address = new InetSocketAddress(InetAddress.getLoopbackAddress(), listenPort)
        httpServer = HttpServer.create(address, 0)
        requestExecutor = Executors.newFixedThreadPool(4)
        httpServer.executor = requestExecutor

        def serverRuntime = this
        httpServer.createContext("/", new HttpHandler() {
            void handle(HttpExchange exchange) {
                serverRuntime.handleLoggedRequest(exchange)
            }
        })
        httpServer.start()
        log("Started on http://127.0.0.1:${boundPort}")
        loader?.registerMenuItem("Server/Log...", this.&showLogWindow as Runnable)
    }

    @Override
    void stop() {
        if (httpServer != null) {
            httpServer.stop(1)
            log("Server stopped.")
        }
        requestExecutor?.shutdown()
        httpServer = null
        requestExecutor = null
        authToken = null
        Files.deleteIfExists(authTokenPath)
        SwingUtilities.invokeLater { logDialog?.dispose() }
    }

    // HTTP

    void handleLoggedRequest(HttpExchange exchange) {
        def startedAt = System.nanoTime()
        int statusCode = 500
        try {
            statusCode = handleRequest(exchange)
        } catch (Throwable error) {
            log("ERROR ${exchange.requestMethod} ${exchange.requestURI.path}: ${error.message}")
            error.printStackTrace(System.err)
            try {
                sendError(exchange, 500, "internal_error", error.message ?: "Unknown error")
            } catch (Exception responseError) {
                log("Failed to send error response: ${responseError.message}")
            }
        } finally {
            exchange.close()
            def elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            logRequest(exchange.requestMethod, exchange.requestURI.path, statusCode, elapsedMillis)
        }
    }

    int handleRequest(HttpExchange exchange) {
        if (exchange.requestHeaders.getFirst("Origin") != null) {
            return sendError(exchange, 403, "forbidden", "Cross-origin requests are not allowed")
        }
        if (authToken == null) {
            return sendError(exchange, 401, "unauthorized", "Server not ready")
        }
        if (exchange.requestHeaders.getFirst("Authorization") != "Bearer ${authToken}") {
            return sendError(exchange, 401, "unauthorized", "Invalid or missing auth token")
        }

        def path = exchange.requestURI.rawPath
        def method = exchange.requestMethod.toUpperCase()
        switch ("${method} ${path}") {
            case "GET /status":
                def models = listModels()
                return sendJson(exchange, 200, [
                    status: "ok", port: boundPort, models: models, modelCount: models.size(),
                ])
            case "GET /models":
                return sendJson(exchange, 200, listModels())
            case "POST /system/script-unsafe":
                return handleScriptRequest(exchange)
            case "GET /loader/plugins":
                if (loader == null) {
                    return sendError(exchange, 503, "loader_unavailable", "Groovy loader not available")
                }
                return sendJson(exchange, 200, loader.listPlugins())
            case "POST /loader/reload":
                return handlePluginReload(exchange)
        }

        if (method == "POST") {
            def modelRoute = MODEL_SCRIPT_ROUTE.matcher(path)
            if (modelRoute.matches()) {
                return handleScriptRequest(exchange, decodePathSegment(modelRoute.group(1)))
            }
            def reloadRoute = PLUGIN_RELOAD_ROUTE.matcher(path)
            if (reloadRoute.matches()) {
                return handlePluginReload(exchange, decodePathSegment(reloadRoute.group(1)))
            }
        }
        return sendError(exchange, 404, "not_found", "Unknown endpoint: ${method} ${path}")
    }

    static String decodePathSegment(String segment) {
        // URLDecoder implements form encoding; '+' is literal in a URL path.
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    }

    static int sendJson(HttpExchange exchange, int statusCode, Object body) {
        def bytes = JsonOutput.toJson(body).getBytes("UTF-8")
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(statusCode, bytes.length)
        exchange.responseBody.withCloseable { it.write(bytes) }
        statusCode
    }

    static int sendError(HttpExchange exchange, int statusCode, String code, String message) {
        sendJson(exchange, statusCode, [error: [code: code, message: message], status: statusCode])
    }

    static def readScriptSource(HttpExchange exchange) {
        def contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
        def body = exchange.requestBody.withReader("UTF-8") { it.text }
        if (!body?.trim()) return null
        contentType.startsWith("application/json") ? new JsonSlurper().parseText(body).script : body
    }

    // Model discovery and script execution

    def getOpenDocuments() {
        QModelDocumentApplication.cFactory.getInstance()?.openDocuments ?: []
    }

    def findDocumentByName(String modelName) {
        modelName ? openDocuments.find { it.name.equalsIgnoreCase(modelName) } : null
    }

    def listModels() {
        openDocuments.collect { doc ->
            [id: doc.name, name: doc.name, dirty: doc.dirty, readOnly: doc.readOnly]
        }
    }

    int handleScriptRequest(HttpExchange exchange, String modelName = null) {
        def source = readScriptSource(exchange)
        if (!source) {
            return sendError(exchange, 400, "empty_script", "Request body must contain a Groovy script")
        }

        boolean sandboxed = modelName != null
        def doc = sandboxed ? findDocumentByName(modelName) : null
        if (sandboxed && !doc) {
            def available = listModels().collect { it.name }
            return sendError(exchange, 404, "model_not_found",
                "Model not found: ${modelName}. Available: ${available}")
        }

        def result
        try {
            SwingUtilities.invokeAndWait {
                result = sandboxed ? evalModelScript(doc, source) : evalUnsafe(source)
            }
        } catch (InvocationTargetException error) {
            def cause = error
            while (cause.cause && cause.cause != cause) cause = cause.cause
            return sendError(exchange, 400, "script_error", cause.message ?: error.message ?: "Script error")
        }
        return sendJson(exchange, 200, [result: serialiseResult(result), sandboxed: sandboxed])
    }

    def evalModelScript(QModelDocument doc, String source) {
        def uiApp = DocumentUIApplication.runningInstance()
        if (!uiApp) throw new RuntimeException("UI application not available")
        def frame = uiApp.getUIForDocument(doc, false)
        if (!frame) throw new RuntimeException("No UI frame for document: ${doc.name}")

        def groovySource = SelectionPreprocessor.preprocessScript(source)
        def context = XGroovyFactory.cInstance.getGroovyContext(frame)
        def compiledScriptClass = XGroovyFactory.cInstance.compileScript(context, groovySource)
        def scriptInstance = compiledScriptClass.getDeclaredConstructor().newInstance()
        registerDocsApi(context, doc)
        context.createBindingOn(scriptInstance)
        frame.perform("Eval", "Eval", { scriptInstance.run() })
    }

    def evalUnsafe(String source) {
        def shell = new GroovyShell(Thread.currentThread().contextClassLoader)
        shell.setVariable("quantrix", QModelDocumentApplication.cFactory.getInstance())
        shell.evaluate(source)
    }

    void registerDocsApi(context, QModelDocument doc) {
        // Introspection is optional; its failure must not prevent script execution.
        try {
            context.registerVariable("api", Object, QxDocs.build(doc))
        } catch (Throwable error) {
            log("QxDocs init failed: ${error.class.name}: ${error.message}")
            error.printStackTrace(System.err)
        }
    }

    static def serialiseResult(Object result) {
        if (result == null || result instanceof Number || result instanceof Boolean
                || result instanceof String || result instanceof Map || result instanceof List) {
            return result
        }
        result.toString()
    }

    // Plugin management

    int handlePluginReload(HttpExchange exchange, String pluginId = null) {
        if (loader == null) {
            return sendError(exchange, 503, "loader_unavailable", "Groovy loader not available")
        }
        if (pluginId != null && !loader.listPlugins().any { it.id == pluginId || it.directory == pluginId }) {
            return sendError(exchange, 404, "plugin_not_found",
                "Plugin not found: ${pluginId}. Use GET /loader/plugins to list.")
        }

        // Finish the response before scheduling a reload that may stop this server.
        def response = pluginId == null ? [status: "reloading"] : [status: "reloading", plugin: pluginId]
        def statusCode = sendJson(exchange, 200, response)
        SwingUtilities.invokeLater {
            if (pluginId == null) loader.reloadAll()
            else loader.reloadById(pluginId)
        }
        statusCode
    }

    // Request log

    static void log(String message) {
        println "[QuantrixServer] ${message}"
    }

    void logRequest(String method, String path, int statusCode, long elapsedMillis) {
        def timestamp = LocalTime.now().format(REQUEST_TIME_FORMAT)
        String line = "${timestamp}  ${statusCode}  ${String.format('%4d', elapsedMillis)}ms  ${method} ${path}"
        SwingUtilities.invokeLater {
            logLines.add(line)
            if (logLines.size() > 500) logLines.remove(0)
            if (logTextArea != null) {
                logTextArea.text = logLines.join("\n")
                logTextArea.caretPosition = logTextArea.document.length
            }
        }
    }

    void showLogWindow() {
        if (logDialog != null) {
            logDialog.toFront()
            return
        }
        def title = httpServer == null ? "Server Log (stopped)" : "Server Log \u2014 127.0.0.1:${boundPort}"
        logDialog = new JDialog((Frame) null, title, false)
        logDialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        def serverRuntime = this
        logDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            void windowClosed(java.awt.event.WindowEvent event) {
                serverRuntime.logTextArea = null
                serverRuntime.logDialog = null
            }
        })

        def panel = new JPanel(new BorderLayout(8, 8))
        panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        logTextArea = new JTextArea(logLines.join("\n"))
        logTextArea.editable = false
        logTextArea.font = new Font(Font.MONOSPACED, Font.PLAIN, 12)
        logTextArea.caretPosition = logTextArea.document.length

        def scrollPane = new JScrollPane(logTextArea)
        scrollPane.preferredSize = new Dimension(700, 400)
        panel.add(scrollPane, BorderLayout.CENTER)

        def buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0))
        def clearButton = new JButton("Clear")
        clearButton.addActionListener { logLines.clear(); logTextArea.text = "" }
        buttons.add(clearButton)
        def closeButton = new JButton("Close")
        closeButton.addActionListener { logDialog.dispose() }
        buttons.add(closeButton)
        panel.add(buttons, BorderLayout.SOUTH)

        logDialog.contentPane = panel
        logDialog.pack()
        logDialog.locationRelativeTo = null
        logDialog.visible = true
    }

    static void writePrivateFileAtomically(Path path, String content) {
        def target = path.toAbsolutePath()
        def temp = Files.createTempFile(target.parent, "${target.fileName}-", ".tmp",
            PosixFilePermissions.asFileAttribute(
                PosixFilePermissions.fromString("rw-------")))
        try {
            Files.write(temp, content.getBytes("UTF-8"))
            Files.move(temp, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

return new QuantrixServerPlugin(loader: loader)
