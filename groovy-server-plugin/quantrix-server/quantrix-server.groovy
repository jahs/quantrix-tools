/*
 * Quantrix HTTP Server Plugin (Groovy)
 *
 * Provides a localhost HTTP server for executing Groovy scripts in the
 * Quantrix scripting context and managing groovy plugins.
 *
 * Endpoints:
 *   GET  /status                         — server health + model list
 *   GET  /models                         — list open models
 *   POST /models/{id}/script             — sandboxed model scripting (undo-wrapped)
 *   POST /system/script-unsafe           — raw Groovy, no sandbox
 *   GET  /loader/plugins                 — list loaded groovy plugins
 *   POST /loader/plugins/{id}/reload     — reload one plugin
 *   POST /loader/reload                  — reload all plugins
 *
 * Script endpoints accept:
 *   Content-Type: text/x-groovy  — body IS the script (preferred)
 *   Content-Type: application/json — {"script": "..."}
 *
 * Authentication:
 *   On startup the server generates a bearer token and writes it to
 *   ~/Library/Application Support/Quantrix/.server-token (mode 0600).
 *   All requests must include:  Authorization: Bearer <token>
 *
 * Loaded by the Groovy Loader Plugin. Returns an IPlugin instance.
 */

import com.subx.framework.IPlugin
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import com.quantrix.core.api.QModelDocument
import com.quantrix.core.api.QModelDocumentApplication
import com.subx.document.ui.iapi.DocumentUIApplication
import com.subx.scripting.core.api.XGroovyFactory
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.transform.Field
import net.jahs.quantrix.preprocessor.SelectionPreprocessor

import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.regex.Pattern
import javax.swing.*
import java.awt.*

// All @Field variables are instance fields on the script class,
// visible to both run() locals and def methods.

@Field String PLUGIN_ID = "net.jahs.quantrix.server"
@Field int SERVER_PORT = 8182
@Field int serverPort = 8182
@Field String TOKEN_PATH = System.getProperty("user.home") + "/Library/Application Support/Quantrix/.server-token"
@Field String authToken = null

// Route patterns
@Field Pattern MODELS_SCRIPT = Pattern.compile('^/models/([^/]+)/script$')
@Field Pattern LOADER_PLUGIN_RELOAD = Pattern.compile('^/loader/plugins/([^/]+)/reload$')

// Request log state
@Field java.util.List logLines = Collections.synchronizedList(new ArrayList<String>())
@Field JTextArea logTextArea = null
@Field JDialog logDialog = null
@Field SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS")

// ── API introspection ───────────────────────────────────────────────
// QxDocs class is compiled from qx-docs.groovy by the Groovy Loader
// Plugin before this entry point runs.  Provides the `api` object
// injected into sandboxed eval scripts.

@Field def qxDocs = null
try {
    qxDocs = QxDocs.build()
} catch (Throwable t) {
    println "[QuantrixServer] QxDocs init failed: ${t.class.name}: ${t.message}"
    t.printStackTrace(System.err)
}

// ── HTTP helpers ────────────────────────────────────────────────────

// Track the response status code for logging (set by sendJson, read by requestHandler)
@Field ThreadLocal<Integer> responseStatus = ThreadLocal.withInitial { 200 }

def sendJson(HttpExchange ex, int status, Object body) {
    responseStatus.set(status)
    def json = (body instanceof String) ? body : JsonOutput.toJson(body)
    def bytes = json.getBytes("UTF-8")
    ex.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    ex.sendResponseHeaders(status, bytes.length)
    ex.responseBody.write(bytes)
    ex.responseBody.close()
}

def sendError(HttpExchange ex, int status, String code, String message) {
    sendJson(ex, status, [error: [code: code, message: message], status: status])
}

def readBody(HttpExchange ex) {
    ex.requestBody.withReader("UTF-8") { it.text }
}

def extractScript(HttpExchange ex) {
    def contentType = ex.requestHeaders.getFirst("Content-Type") ?: ""
    def body = readBody(ex)
    if (!body?.trim()) return null
    if (contentType.startsWith("application/json")) {
        return new JsonSlurper().parseText(body).script
    }
    // text/x-groovy, text/plain, or anything else — body IS the script
    return body
}

// ── Model resolution ────────────────────────────────────────────────

def getApp() {
    QModelDocumentApplication.cFactory.getInstance()
}

def resolveDocument(String modelId) {
    def app = getApp()
    if (!app) return null
    def docs = app.openDocuments
    if (!docs) return null
    if (!modelId) return null
    docs.find { it.name.equalsIgnoreCase(modelId) }
}

def listModels() {
    def app = getApp()
    if (!app) return []
    (app.openDocuments ?: []).collect { doc ->
        [id: doc.name, name: doc.name, dirty: doc.dirty, readOnly: doc.readOnly]
    }
}

// ── Eval engines ────────────────────────────────────────────────────

def evalScript(QModelDocument doc, String script) {
    def uiApp = DocumentUIApplication.runningInstance()
    if (!uiApp) throw new RuntimeException("UI application not available")

    def frame = uiApp.getUIForDocument(doc, false)
    if (!frame) throw new RuntimeException("No UI frame for document: ${doc.name}")

    // Preprocess pipe syntax (|...|) → getSelection("...") before compilation
    script = SelectionPreprocessor.preprocessScript(script)

    def context = XGroovyFactory.cInstance.getGroovyContext(frame)
    def scriptClass = XGroovyFactory.cInstance.compileScript(context, script)
    def scriptInstance = scriptClass.getDeclaredConstructor().newInstance()
    if (qxDocs != null) context.registerVariable("api", Object, qxDocs)
    context.createBindingOn(scriptInstance)

    def result = frame.perform("Eval", "Eval", {
        return scriptInstance.run()
    })
    return result
}

def evalUnsafe(String script) {
    def shell = new groovy.lang.GroovyShell(Thread.currentThread().contextClassLoader)
    shell.setVariable("quantrix", QModelDocumentApplication.cFactory.getInstance())
    return shell.evaluate(script)
}

def serialiseResult(Object result) {
    if (result == null) return null
    if (result instanceof Number || result instanceof Boolean || result instanceof String) {
        return result
    }
    if (result instanceof Map || result instanceof List) {
        return result
    }
    return result.toString()
}

// ── Request handler ─────────────────────────────────────────────────

def handleRequest(HttpExchange ex) {
    def path = ex.requestURI.path
    def method = ex.requestMethod.toUpperCase()

    // Block browser cross-origin requests. Browsers always send an Origin
    // header on cross-origin requests; CLI tools (curl, Python) never do.
    // This prevents malicious websites from reaching the server via fetch().
    def origin = ex.requestHeaders.getFirst("Origin")
    if (origin != null) {
        sendError(ex, 403, "forbidden", "Cross-origin requests are not allowed")
        return
    }

    // Require bearer token authentication
    if (authToken == null) {
        sendError(ex, 401, "unauthorized", "Server not ready")
        return
    }
    def authHeader = ex.requestHeaders.getFirst("Authorization")
    if (authHeader != "Bearer ${authToken}") {
        sendError(ex, 401, "unauthorized", "Invalid or missing auth token")
        return
    }

    try {
        // ── Static routes ───────────────────────────────────────────

        if (path == "/status" && method == "GET") {
            def models = listModels()
            sendJson(ex, 200, [
                status: "ok",
                port: serverPort,
                models: models,
                modelCount: models.size(),
            ])
            return
        }

        if (path == "/models" && method == "GET") {
            sendJson(ex, 200, listModels())
            return
        }

        if (path == "/system/script-unsafe" && method == "POST") {
            handleUnsafeScript(ex)
            return
        }

        if (path == "/loader/plugins" && method == "GET") {
            handleListPlugins(ex)
            return
        }

        if (path == "/loader/reload" && method == "POST") {
            handleReloadAll(ex)
            return
        }

        // ── Parameterised routes ────────────────────────────────────

        def m = MODELS_SCRIPT.matcher(path)
        if (m.matches() && method == "POST") {
            handleModelScript(ex, URLDecoder.decode(m.group(1), "UTF-8"))
            return
        }

        m = LOADER_PLUGIN_RELOAD.matcher(path)
        if (m.matches() && method == "POST") {
            handlePluginReload(ex, URLDecoder.decode(m.group(1), "UTF-8"))
            return
        }

        sendError(ex, 404, "not_found", "Unknown endpoint: ${method} ${path}")

    } catch (Exception e) {
        println "[QuantrixServer] ERROR ${method} ${path}: ${e.message}"
        e.printStackTrace(System.err)
        try {
            sendError(ex, 500, "internal_error", e.message ?: "Unknown error")
        } catch (Exception e2) {
            System.err.println("[QuantrixServer] Failed to send error response: ${e2.message}")
        }
    }
}

// ── Endpoint handlers ───────────────────────────────────────────────

def handleModelScript(HttpExchange ex, String modelId) {
    def script = extractScript(ex)
    if (!script) {
        sendError(ex, 400, "empty_script", "Request body must contain a Groovy script")
        return
    }

    def doc = resolveDocument(modelId)
    if (!doc) {
        def available = listModels().collect { it.name }
        sendError(ex, 404, "model_not_found",
            "Model not found: ${modelId}. Available: ${available}")
        return
    }

    def result = null
    def error = null

    SwingUtilities.invokeAndWait {
        try {
            result = evalScript(doc, script)
        } catch (Exception e) {
            error = e
        }
    }

    if (error) {
        def cause = error
        while (cause.cause && cause.cause != cause) cause = cause.cause
        sendError(ex, 400, "script_error", cause.message ?: error.message ?: "Script error")
        return
    }

    sendJson(ex, 200, [result: serialiseResult(result), sandboxed: true])
}

def handleUnsafeScript(HttpExchange ex) {
    def script = extractScript(ex)
    if (!script) {
        sendError(ex, 400, "empty_script", "Request body must contain a Groovy script")
        return
    }

    def result = null
    def error = null

    SwingUtilities.invokeAndWait {
        try {
            result = evalUnsafe(script)
        } catch (Exception e) {
            error = e
        }
    }

    if (error) {
        def cause = error
        while (cause.cause && cause.cause != cause) cause = cause.cause
        sendError(ex, 400, "script_error", cause.message ?: error.message ?: "Script error")
        return
    }

    sendJson(ex, 200, [result: serialiseResult(result), sandboxed: false])
}

def handleListPlugins(HttpExchange ex) {
    if (loader == null) {
        sendError(ex, 503, "loader_unavailable", "Groovy loader not available")
        return
    }
    sendJson(ex, 200, loader.listPlugins())
}

def handlePluginReload(HttpExchange ex, String pluginId) {
    if (loader == null) {
        sendError(ex, 503, "loader_unavailable", "Groovy loader not available")
        return
    }
    // Check plugin exists before responding
    def plugins = loader.listPlugins()
    def found = plugins.any { it.id == pluginId || it.directory == pluginId }
    if (!found) {
        sendError(ex, 404, "plugin_not_found",
            "Plugin not found: ${pluginId}. Use GET /loader/plugins to list.")
        return
    }
    // Respond before reloading — the target plugin may be this server
    sendJson(ex, 200, [status: "reloading", plugin: pluginId])
    SwingUtilities.invokeLater { loader.reloadById(pluginId) }
}

def handleReloadAll(HttpExchange ex) {
    if (loader == null) {
        sendError(ex, 503, "loader_unavailable", "Groovy loader not available")
        return
    }
    // Respond before reloading — this server will be stopped and restarted
    sendJson(ex, 200, [status: "reloading"])
    // Schedule reload on EDT after response is sent
    SwingUtilities.invokeLater { loader.reloadAll() }
}

// ── Request log ─────────────────────────────────────────────────────

def logRequest(String method, String path, int status, long ms) {
    def ts = dateFormat.format(new Date())
    def line = "${ts}  ${status}  ${String.format('%4d', ms)}ms  ${method} ${path}"
    logLines.add(line)
    while (logLines.size() > 500) logLines.remove(0)
    if (logTextArea != null) {
        SwingUtilities.invokeLater {
            logTextArea.append(line + "\n")
            logTextArea.caretPosition = logTextArea.document.length
        }
    }
}

def showLogWindow() {
    if (logDialog != null) {
        logDialog.toFront()
        return
    }
    logDialog = new JDialog((Frame) null, "Server Log \u2014 127.0.0.1:${serverPort}", false)
    logDialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
    logDialog.addWindowListener(new java.awt.event.WindowAdapter() {
        void windowClosed(java.awt.event.WindowEvent e) {
            logTextArea = null
            logDialog = null
        }
    })

    def panel = new JPanel(new BorderLayout(8, 8))
    panel.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

    logTextArea = new JTextArea()
    logTextArea.editable = false
    logTextArea.font = new Font(Font.MONOSPACED, Font.PLAIN, 12)
    logLines.each { logTextArea.append(it + "\n") }
    logTextArea.caretPosition = logTextArea.document.length

    def scrollPane = new JScrollPane(logTextArea)
    scrollPane.preferredSize = new Dimension(700, 400)
    panel.add(scrollPane, BorderLayout.CENTER)

    def buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0))
    def clearBtn = new JButton("Clear")
    clearBtn.addActionListener { logLines.clear(); logTextArea.text = "" }
    buttons.add(clearBtn)
    def closeBtn = new JButton("Close")
    closeBtn.addActionListener { logDialog.dispose() }
    buttons.add(closeBtn)
    panel.add(buttons, BorderLayout.SOUTH)

    logDialog.contentPane = panel
    logDialog.pack()
    logDialog.locationRelativeTo = null
    logDialog.visible = true
}

// ── Plugin bootstrap ────────────────────────────────────────────────

// Capture script-level references for the inner class.
// The IPlugin.Adapter anonymous class can't see script methods directly,
// so we capture closures that delegate to them.
def _handleRequest = this.&handleRequest
def _logRequest = this.&logRequest
def _showLogWindow = this.&showLogWindow

def requestHandler = { HttpExchange ex ->
    def start = System.currentTimeMillis()
    def method = ex.requestMethod
    def path = ex.requestURI.path
    responseStatus.set(200)
    try {
        _handleRequest(ex)
        def ms = System.currentTimeMillis() - start
        _logRequest(method, path, responseStatus.get(), ms)
    } catch (Throwable t) {
        def ms = System.currentTimeMillis() - start
        _logRequest(method, path, 500, ms)
        throw t
    }
} as Closure

// Capture values for the inner class (anonymous classes can't see @Field directly)
def _server = null
def _executor = null
def _pluginId = PLUGIN_ID
def _serverPort = SERVER_PORT
def _log = { msg -> println "[QuantrixServer] $msg" }
def _script = this  // reference to the script instance for setting serverPort
def _tokenPath = TOKEN_PATH

return new IPlugin.Adapter() {
    String getId() { _pluginId }
    String getVersion() { getClass().package?.implementationVersion ?: "dev" }

    void start() {
        // Generate auth token and write to file
        def token = UUID.randomUUID().toString()
        def tokenFile = new File(_tokenPath)
        tokenFile.text = token
        Files.setPosixFilePermissions(tokenFile.toPath(),
            PosixFilePermissions.fromString("rw-------"))
        _script.authToken = token
        _log "Auth token written to ${_tokenPath}"

        def addr = new InetSocketAddress(InetAddress.getLoopbackAddress(), _serverPort)
        _server = HttpServer.create(addr, 0)
        _executor = Executors.newFixedThreadPool(4)
        _server.setExecutor(_executor)

        final Closure handler = requestHandler
        _server.createContext("/", new com.sun.net.httpserver.HttpHandler() {
            void handle(HttpExchange exchange) {
                try {
                    handler.call(exchange)
                } catch (Throwable t) {
                    System.err.println("[QuantrixServer] Handler error: " + t)
                    t.printStackTrace(System.err)
                    try {
                        exchange.sendResponseHeaders(500, -1)
                        exchange.close()
                    } catch (Exception ignored) {}
                }
            }
        })
        _server.start()
        _script.serverPort = _server.address.port
        _log "Started on http://127.0.0.1:${_script.serverPort}"

        if (loader != null) {
            loader.registerMenuItem("Server/Log...", { _showLogWindow() } as Runnable)
        }
    }

    void stop() {
        if (_server) {
            _server.stop(1)
            _log "Server stopped."
        }
        if (_executor) {
            _executor.shutdown()
        }
        _server = null
        _executor = null
        _script.authToken = null
        new File(_tokenPath).delete()
    }
}
