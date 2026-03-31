/*
 * QxDocs — API introspection for Quantrix scripting.
 *
 * Exposes an Expando object (injected as `api` in sandboxed eval) that lets
 * scripts discover SAPI types, formula functions, and resolve dot-chains at
 * runtime.  Built on top of Quantrix's internal BoundType reflection wrappers
 * and QFunctionRegistry.
 *
 * Usage from a sandboxed script:
 *   api.types()                    — list all SAPI type names
 *   api.type("Matrix")             — methods + properties + docs for a type
 *   api.member("Model", "save")    — single member detail
 *   api.resolve("model.matrices.getAt.categories")  — walk a dot-chain
 *   api.functions()                — all 200+ formula functions
 *   api.functions("Financial")     — functions in one category
 *   api.function("IF")             — single function detail
 *   api.categories()               — formula function categories
 *   api.search("formula")          — search across types + functions
 *
 * Compiled automatically by the Groovy Loader Plugin (helper .groovy file).
 */

class QxDocs {

    // Packages whose types are indexed
    private static final Set<String> SAPI_PACKAGES = [
        "com.quantrix.scripting.core.sapi",
        "com.subx.scripting.core.sapi",
    ] as Set

    // ── BoundType helpers ──────────────────────────────────────────
    // Use explicit Java method calls to avoid Groovy metaclass
    // resolution issues in the plugin classloader context.

    private static String btClassName(bt)  { bt?.getBaseClass()?.getName() }
    private static String btSimpleName(bt) { bt?.getSimpleName() }

    private static boolean isSapiType(bt) {
        def className = btClassName(bt)
        if (!className) return false
        SAPI_PACKAGES.any { pkg -> className.startsWith(pkg + ".") }
    }

    private static Map extractMethodMap(m) {
        def info = [name: m.getName(), doc: m.getDocumentation(),
                    returnType: btSimpleName(m.getReturnType())]
        try {
            info.params = m.getParameterNames().withIndex().collect { pname, i ->
                def types = m.getParameterTypes()
                [name: pname, type: (i < types.size() ? btSimpleName(types[i]) : null)]
            }
        } catch (e) { info.params = [] }
        try { info.deprecated = m.isDeprecated() } catch (e) { info.deprecated = false }
        info
    }

    private static Map extractPropertyMap(p) {
        [name: p.getName(), doc: p.getDocumentation(), type: btSimpleName(p.getType())]
    }

    // ── Type discovery ─────────────────────────────────────────────

    private static Map buildTypeCache() {
        def BoundType = Class.forName("com.subx.scripting.core.iapi.types.BoundType")
        def cache = [:]
        def queue = []

        try {
            queue << BoundType.get(Class.forName("com.quantrix.scripting.core.sapi.Model"))
        } catch (e) {
            println "[QxDocs] failed to seed Model type: ${e.message}"
            return cache
        }

        def seen = new HashSet<String>()
        while (queue) {
            def bt = queue.remove(0)
            def name = btSimpleName(bt)
            def fullName = btClassName(bt)
            if (seen.contains(fullName)) continue
            seen << fullName

            def methods = bt.getDeclaredPublicInstanceMethods()
                .collect { extractMethodMap(it) }.sort { it.name }
            def properties = bt.getDeclaredProperties()
                .collect { extractPropertyMap(it) }.sort { it.name }
            cache[name] = [name: name, fullName: fullName,
                           methods: methods, properties: properties, bt: bt]

            bt.getDeclaredPublicInstanceMethods().each { m ->
                def rt = m.getReturnType()
                if (rt && isSapiType(rt) && !seen.contains(btClassName(rt))) queue << rt
            }
            bt.getDeclaredProperties().each { p ->
                def pt = p.getType()
                if (pt && isSapiType(pt) && !seen.contains(btClassName(pt))) queue << pt
            }

            try {
                bt.getBaseClass()?.getInterfaces()?.each { iface ->
                    if (SAPI_PACKAGES.any { pkg -> iface.getName().startsWith(pkg + ".") }) {
                        def ifBt = BoundType.get(iface)
                        if (!seen.contains(iface.getName())) queue << ifBt
                    }
                }
            } catch (e) { /* skip */ }
        }

        println "[QxDocs] indexed ${cache.size()} types: ${cache.keySet().sort().join(', ')}"
        cache
    }

    // ── Formula function discovery ─────────────────────────────────

    private static Map buildFunctionCache() {
        def allFunctions = []
        def byName = [:]
        def byCategory = [:]

        try {
            def QFunctionRegistry = Class.forName("com.quantrix.engine.api.formula.QFunctionRegistry")
            def registry = QFunctionRegistry.cSingleton.getInstance()

            registry.getCategories().each { cat ->
                def catFunctions = []
                registry.getFunctionsInCategory(cat)?.each { fname ->
                    def fn = registry.getFunctionNamed(fname)
                    if (!fn) return
                    def argNames = fn.getArgumentNames() ?: [] as String[]
                    def argDocs  = fn.getArgumentDocs()  ?: [] as String[]
                    def argTypes = fn.getArgumentTypes()  ?: []
                    def args = []
                    for (int i = 0; i < argNames.length; i++) {
                        args << [
                            name: argNames[i],
                            type: (i < argTypes.length ? argTypes[i]?.name() : null),
                            doc:  (i < argDocs.length  ? argDocs[i]           : null),
                        ]
                    }
                    def info = [
                        name: fn.getName(), category: cat,
                        description: fn.getDescription(), args: args,
                        returnType: fn.getReturnType()?.name(), infix: fn.isInfix(),
                    ]
                    allFunctions << info
                    catFunctions << info
                    byName[fn.getName().toLowerCase()] = info
                }
                byCategory[cat] = catFunctions
            }
        } catch (e) {
            println "[QxDocs] failed to index formula functions: ${e.message}"
        }

        println "[QxDocs] indexed ${allFunctions.size()} formula functions"
        [all: allFunctions, byName: byName, byCategory: byCategory]
    }

    // ── Public factory ─────────────────────────────────────────────

    static Expando build() {
        def typeCache = buildTypeCache()
        def funcCache = buildFunctionCache()

        def api = new Expando()

        // ── Scripting API types ──

        api.types = { ->
            typeCache.values().collect { t ->
                [name: t.name, methods: t.methods.size(), properties: t.properties.size()]
            }.sort { it.name }
        }

        api.type = { String name ->
            def t = typeCache[name]
            if (!t) return [error: "Unknown type: ${name}. Available: ${typeCache.keySet().sort().join(', ')}"]
            [name: t.name, methods: t.methods, properties: t.properties]
        }

        api.member = { String typeName, String memberName ->
            def t = typeCache[typeName]
            if (!t) return [error: "Unknown type: ${typeName}"]
            def methods = t.methods.findAll { it.name == memberName }
            def props = t.properties.findAll { it.name == memberName }
            if (!methods && !props) return [error: "No member '${memberName}' on ${typeName}"]
            def result = [:]
            if (methods) result.methods = methods
            if (props) result.properties = props
            result
        }

        api.resolve = { String chain ->
            def parts = chain.split(/\./)
            if (!parts) return [error: "Empty chain"]
            def steps = []
            def current = null

            def root = parts[0]
            def modelEntry = typeCache["Model"]
            if (root == "model" && modelEntry) {
                current = modelEntry.bt
                steps << [expr: root, type: btSimpleName(current)]
            } else if (typeCache[root]) {
                current = typeCache[root].bt
                steps << [expr: root, type: btSimpleName(current)]
            } else {
                return [error: "Unknown root: ${root}", available: typeCache.keySet().sort()]
            }

            for (int i = 1; i < parts.length && current; i++) {
                def seg = parts[i]
                def next = null

                def prop = current.getAllProperties().find { it.getName() == seg }
                if (prop) {
                    next = prop.getType()
                } else {
                    def method = current.getAllPublicInstanceMethods().find {
                        it.getName() == seg && !it.getParameterTypes()
                    }
                    if (!method) method = current.getAllPublicInstanceMethods().find {
                        it.getName() == seg
                    }
                    if (method) next = method.getReturnType()
                }

                if (next) {
                    steps << [expr: seg, type: btSimpleName(next)]
                    current = next
                } else {
                    steps << [expr: seg, type: null, error: "not found on ${btSimpleName(current)}"]
                    break
                }
            }

            def result = [steps: steps]
            if (current) {
                def allMethods = current.getAllPublicInstanceMethods().collect { m ->
                    m.getName() + "(" + (m.getParameterNames()?.join(", ") ?: "") + "): " +
                        btSimpleName(m.getReturnType())
                }.sort().unique()
                def allProps = current.getAllProperties().collect { p ->
                    p.getName() + ": " + btSimpleName(p.getType())
                }.sort()
                result.leaf = [type: btSimpleName(current),
                               methods: allMethods, properties: allProps]
            }
            result
        }

        // ── Formula functions ──

        api.categories = { -> funcCache.byCategory.keySet().sort() }

        api.functions = { Object... args ->
            if (args.length == 0) return funcCache.all
            def cat = args[0] as String
            def result = funcCache.byCategory[cat]
            if (result == null) return [error: "Unknown category: ${cat}. Available: ${funcCache.byCategory.keySet().sort().join(', ')}"]
            result
        }

        api.function = { String name ->
            def fn = funcCache.byName[name.toLowerCase()]
            if (!fn) return [error: "Unknown function: ${name}"]
            fn
        }

        // ── Search ──

        api.search = { String query ->
            def q = query.toLowerCase()
            def results = []

            typeCache.each { typeName, t ->
                t.methods.each { m ->
                    if (m.name.toLowerCase().contains(q) || m.doc?.toLowerCase()?.contains(q)) {
                        results << [type: typeName, name: m.name, kind: "method", doc: m.doc]
                    }
                }
                t.properties.each { p ->
                    if (p.name.toLowerCase().contains(q) || p.doc?.toLowerCase()?.contains(q)) {
                        results << [type: typeName, name: p.name, kind: "property", doc: p.doc]
                    }
                }
            }

            funcCache.all.each { fn ->
                if (fn.name.toLowerCase().contains(q) || fn.description?.toLowerCase()?.contains(q)) {
                    results << [name: fn.name, kind: "function", category: fn.category, doc: fn.description]
                }
            }

            results
        }

        api.toString = { ->
            "QxDocs: ${typeCache.size()} types, ${funcCache.all.size()} functions. " +
            "Use api.types(), api.type(name), api.functions(), api.search(q)"
        }

        api
    }
}
