/*
 * Run with ../test-server.sh. Uses API stubs by default, or real Quantrix
 * classes with QX_APP set. Document discovery and evaluation are mocked in
 * both modes; the Quantrix sandbox and undo implementation are not exercised.
 * The HTTP listener binds an ephemeral port; all files live in a temp directory.
 */
import com.quantrix.core.api.QModelDocument
import com.subx.framework.IPlugin
import com.sun.net.httpserver.*
import groovy.json.JsonSlurper
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import javax.swing.*

class RecordingOutput extends ByteArrayOutputStream {
    boolean closed
    void close() { closed = true }
}

class RecordingExchange extends HttpExchange {
    Headers requestHeaders = new Headers()
    Headers responseHeaders = new Headers()
    URI requestURI
    String requestMethod
    InputStream requestBody = new ByteArrayInputStream(new byte[0])
    RecordingOutput output = new RecordingOutput()
    int responseCode = -1
    long responseLength
    boolean closed
    Map attributes = [:]

    OutputStream getResponseBody() { output }
    HttpContext getHttpContext() { null }
    InetSocketAddress getRemoteAddress() { new InetSocketAddress(0) }
    InetSocketAddress getLocalAddress() { new InetSocketAddress(0) }
    String getProtocol() { "HTTP/1.1" }
    HttpPrincipal getPrincipal() { null }
    Object getAttribute(String name) { attributes[name] }
    void setAttribute(String name, Object value) { attributes[name] = value }
    void setStreams(InputStream input, OutputStream output) { throw new UnsupportedOperationException() }

    void sendResponseHeaders(int statusCode, long length) {
        assert responseCode == -1: "Response already sent"
        responseCode = statusCode
        responseLength = length
    }

    void close() {
        requestBody.close()
        output.close()
        closed = true
    }

    Object json() { new JsonSlurper().parseText(output.toString("UTF-8")) }
}

class RecordingLoader {
    List plugins = [[id: 'test+plugin%id', directory: 'test-plugin', status: 'running']]
    List reloads = []
    List menuItems = []
    RecordingExchange exchange

    List listPlugins() { plugins }
    void registerMenuItem(String name, Runnable action) { menuItems << name }
    void reloadAll() { recordReload(null) }
    void reloadById(String id) { recordReload(id) }

    void recordReload(String id) {
        reloads << [id: id, onEdt: SwingUtilities.isEventDispatchThread(), responseClosed: exchange.output.closed]
    }
}

def pluginDir = new File(args ? args[0] : '.').canonicalFile
def classLoader = new GroovyClassLoader()
classLoader.parseClass(new File(pluginDir, 'quantrix-server/qx-docs.groovy'))
def loader = new RecordingLoader()
def shell = new GroovyShell(classLoader, new Binding([loader: loader]))
def server = shell.evaluate(new File(pluginDir, 'quantrix-server/quantrix-server.groovy'))
def tempDir = Files.createTempDirectory('qx-server-test-')
def flushEdt = { SwingUtilities.invokeAndWait {} }
def passed = 0
def test = { String name, Closure assertions ->
    assertions()
    passed++
    println "PASS: ${name}"
}

try {
    assert server instanceof IPlugin
    assert server.id == 'net.jahs.quantrix.server'
    server.listenPort = 0
    server.authTokenPath = tempDir.resolve('.server-token')

    def modelNames = ['Budget', 'Plan+A', 'Plan%20A', 'Plan/A', 'Plan%A']
    def documents = modelNames.collect { name ->
        [getName: { name }, isDirty: { false }, isReadOnly: { false }] as QModelDocument
    }
    server.metaClass.getOpenDocuments = { -> documents }
    server.metaClass.evalModelScript = { QModelDocument doc, String source ->
        assert SwingUtilities.isEventDispatchThread()
        new GroovyShell(new Binding([model: [name: doc.name]])).evaluate(source)
    }
    server.metaClass.evalUnsafe = { String source ->
        assert SwingUtilities.isEventDispatchThread()
        new GroovyShell().evaluate(source)
    }

    server.start()
    def token = Files.readString(server.authTokenPath)
    def request = { String method, String path, String source = '', Map headers = [:], boolean authenticated = true ->
        def exchange = new RecordingExchange(
            requestMethod: method, requestURI: URI.create(path),
            requestBody: new ByteArrayInputStream(source.getBytes('UTF-8')))
        if (authenticated) exchange.requestHeaders.set('Authorization', "Bearer ${token}")
        headers.each { name, value -> exchange.requestHeaders.set(name, value) }
        loader.exchange = exchange
        server.handleLoggedRequest(exchange)
        assert exchange.closed
        assert exchange.responseLength == exchange.output.size()
        exchange
    }

    test('the packaged JAR returns the same plugin implementation') {
        def pluginJar = new File(pluginDir, 'dist').listFiles().find { it.name.startsWith('quantrix-server-') }
        def jarBase = "jar:${pluginJar.toURI()}!/"
        def jarClassLoader = new GroovyClassLoader()
        def jarShell = new GroovyShell(jarClassLoader, new Binding([loader: null]))
        try {
            jarClassLoader.parseClass(new GroovyCodeSource(new URL(jarBase + 'qx-docs.groovy')))
            def plugin = jarShell.evaluate(new GroovyCodeSource(new URL(jarBase + 'quantrix-server.groovy')))
            assert plugin instanceof IPlugin
            assert plugin.id == server.id
            assert plugin.class.name == 'QuantrixServerPlugin'
        } finally {
            jarShell.classLoader.close()
            jarClassLoader.close()
        }
    }
    test('startup publishes a private token and registers the menu') {
        assert UUID.fromString(token)
        assert Files.getPosixFilePermissions(server.authTokenPath) == PosixFilePermissions.fromString('rw-------')
        assert loader.menuItems == ['Server/Log...']
        assert server.boundPort > 0
    }
    test('missing and invalid authentication') {
        assert request('GET', '/status', '', [:], false).responseCode == 401
        assert request('GET', '/status', '', [Authorization: 'Bearer wrong']).responseCode == 401
    }
    test('Origin rejection precedes authentication') {
        def response = request('GET', '/status', '', [Origin: 'https://example.org'], false)
        assert response.responseCode == 403
        assert response.json().error.code == 'forbidden'
    }
    test('status and model discovery preserve the response schema') {
        def response = request('GET', '/status')
        assert response.responseCode == 200
        assert response.json().keySet() == ['status', 'port', 'models', 'modelCount'] as Set
        assert response.json().port == server.boundPort
        assert response.json().modelCount == modelNames.size()
        assert request('GET', '/models').json() == modelNames.collect {
            [id: it, name: it, dirty: false, readOnly: false]
        }
    }
    test('the actual HTTP adapter serves an isolated listener') {
        def url = new URI('http', null, InetAddress.getLoopbackAddress().hostAddress,
            server.boundPort, '/status', null, null).toURL()
        def connection = url.openConnection()
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty('Authorization', "Bearer ${token}")
        try {
            assert connection.responseCode == 200
            assert new JsonSlurper().parse(connection.inputStream).port == server.boundPort
        } finally {
            connection.disconnect()
        }
    }
    test('model and unsafe evaluation run on the EDT') {
        assert request('POST', '/models/budget/script', '1 + 2').json() == [result: 3, sandboxed: true]
        assert request('POST', '/system/script-unsafe', '1 + 2').json() == [result: 3, sandboxed: false]
    }
    test('JSON and raw source request bodies') {
        assert request('POST', '/models/Budget/script', '{"script":"6 * 7"}',
            ['Content-Type': 'application/json; charset=utf-8']).json().result == 42
        assert request('POST', '/models/Budget/script', '"café"',
            ['Content-Type': 'text/x-groovy']).json().result == 'café'
    }
    test('empty source is rejected on both endpoints') {
        ['/models/Budget/script', '/system/script-unsafe'].each { path ->
            ['', ' \n\t'].each { source ->
                def response = request('POST', path, source)
                assert response.responseCode == 400
                assert response.json().error.code == 'empty_script'
            }
        }
    }
    test('missing models do not fall back to unsafe evaluation') {
        def response = request('POST', '/models/missing/script', '1 + 2')
        assert response.responseCode == 404
        assert response.json().error.code == 'model_not_found'
    }
    test('script errors report the root cause on both endpoints') {
        ['/models/Budget/script', '/system/script-unsafe'].each { path ->
            def response = request('POST', path,
                'throw new RuntimeException("outer", new IllegalArgumentException("inner"))')
            assert response.responseCode == 400
            assert response.json().error == [code: 'script_error', message: 'inner']
        }
    }
    test('result conversion preserves JSON values and stringifies other objects') {
        ['null', 'true', '42', '"hello"', '[a: 1]', '[1, 2]', 'new StringBuilder("text")'].each { source ->
            def expected = new GroovyShell().evaluate(source)
            if (expected instanceof StringBuilder) expected = expected.toString()
            assert request('POST', '/system/script-unsafe', source).json().result == expected
        }
    }
    test('model names are decoded once, preserving plus signs and encoded slashes') {
        modelNames.each { name ->
            def encoded = URLEncoder.encode(name, 'UTF-8').replace('+', '%20')
            assert request('POST', "/models/${encoded}/script", 'model.name').json().result == name
        }
        assert request('POST', '/models/Plan+A/script', 'model.name').json().result == 'Plan+A'
    }
    test('unknown routes and unsupported methods return 404') {
        assert request('GET', '/missing').responseCode == 404
        assert request('GET', '/models/Budget/script').responseCode == 404
        assert request('DELETE', '/loader/reload').responseCode == 404
    }
    test('unexpected request failures return JSON errors and are logged once') {
        flushEdt()
        def previousLogSize = server.logLines.size()
        def response = request('POST', '/system/script-unsafe', '{not valid JSON', ['Content-Type': 'application/json'])
        assert response.responseCode == 500
        assert response.json().error.code == 'internal_error'
        flushEdt()
        assert server.logLines.last().contains('500')
        assert server.logLines.size() == previousLogSize + 1
    }
    test('plugin listing and unavailable-loader errors') {
        assert request('GET', '/loader/plugins').json() == loader.plugins
        server.loader = null
        try {
            [['GET', '/loader/plugins'], ['POST', '/loader/reload'],
             ['POST', '/loader/plugins/test-plugin/reload']].each { route ->
                def response = request(*route)
                assert response.responseCode == 503
                assert response.json().error.code == 'loader_unavailable'
            }
        } finally {
            server.loader = loader
        }
    }
    test('unknown plugins return 404 without scheduling a reload') {
        assert request('POST', '/loader/plugins/missing/reload').responseCode == 404
        flushEdt()
        assert loader.reloads.empty
    }
    test('reload responses finish before the EDT reload callback') {
        ['test+plugin%id', 'test-plugin', null].each { id ->
            def path = id == null ? '/loader/reload' :
                "/loader/plugins/${URLEncoder.encode(id, 'UTF-8')}/reload"
            def response = request('POST', path)
            assert response.responseCode == 200
            assert response.json() == (id == null ? [status: 'reloading'] : [status: 'reloading', plugin: id])
            flushEdt()
            assert loader.reloads.last() == [id: id, onEdt: true, responseClosed: true]
        }
    }
    test('concurrent request logs have bounded history and display') {
        SwingUtilities.invokeAndWait { server.logTextArea = new JTextArea() }
        def workers = Executors.newFixedThreadPool(4)
        try {
            def jobs = (0..<520).collect { index ->
                workers.submit({ server.logRequest('GET', "/log/${index}", 200, index) } as Runnable)
            }
            jobs.each { it.get() }
            flushEdt()
            assert server.logLines.size() == 500
            assert server.logTextArea.text.readLines().size() == 500
            assert server.logTextArea.text == server.logLines.join('\n')
        } finally {
            workers.shutdown()
        }
    }

    test('private atomic writes protect new, existing and linked files, and clean up failures') {
        def privateMode = PosixFilePermissions.fromString('rw-------')
        def writeMethod = Files.getMethod('write', Path, byte[].class, OpenOption[].class)
        def writes = 0
        def failWrite = false
        Files.metaClass.static.write = { Path path, byte[] bytes ->
            assert Files.getPosixFilePermissions(path) == privateMode
            writes++
            if (failWrite) throw new IOException('injected write failure')
            writeMethod.invoke(null, path, bytes, [] as OpenOption[])
        }
        try {
            ['new', 'existing', 'symlink', 'hardlink', 'write-failure', 'move-failure'].each { scenario ->
                def directory = Files.createDirectory(tempDir.resolve(scenario))
                def target = directory.resolve('token')
                def other = directory.resolve('other')
                if (scenario in ['existing', 'write-failure']) {
                    target.toFile().text = 'old'
                    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString('rw-r--r--'))
                }
                if (scenario in ['symlink', 'hardlink']) {
                    other.toFile().text = 'untouched'
                    if (scenario == 'symlink') Files.createSymbolicLink(target, other)
                    else Files.createLink(target, other)
                }
                if (scenario == 'move-failure') {
                    Files.createDirectory(target)
                    target.resolve('keep').toFile().text = 'untouched'
                }
                failWrite = scenario == 'write-failure'
                boolean failed = false
                try {
                    server.writePrivateFileAtomically(target, 'new')
                } catch (IOException expected) {
                    failed = true
                }
                assert failed == (scenario in ['write-failure', 'move-failure'])
                if (!failed) {
                    assert Files.getPosixFilePermissions(target) == privateMode
                    assert target.toFile().text == 'new'
                    assert !Files.isSymbolicLink(target)
                }
                if (scenario in ['symlink', 'hardlink']) assert other.toFile().text == 'untouched'
                if (scenario == 'write-failure') assert target.toFile().text == 'old'
                assert !directory.toFile().list().any { it.endsWith('.tmp') }
            }
            assert writes == 6
        } finally {
            GroovySystem.metaClassRegistry.removeMetaClass(Files)
        }
    }

    test('stop removes the token and rejects further authenticated requests') {
        server.stop()
        assert !Files.exists(server.authTokenPath)
        def response = request('GET', '/models')
        assert response.responseCode == 401
        assert response.json().error.message == 'Server not ready'
    }
    println "\n${passed} server tests passed"
} finally {
    server.stop()
    flushEdt()
    tempDir.toFile().deleteDir()
    shell.classLoader.close()
    classLoader.close()
}
