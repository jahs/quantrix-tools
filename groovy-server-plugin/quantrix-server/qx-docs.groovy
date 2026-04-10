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
 *   api.problems()                 — document-level problems from ProblemManager
 *   api.warnings()                 — formula warnings/errors/eclipses + problems
 *
 * Compiled automatically by the Groovy Loader Plugin (helper .groovy file).
 * The current document is passed in internally when building `api`, but is not
 * exposed directly as a sandbox variable.
 */

class QxDocs {

    // Packages whose types are indexed
    private static final Set<String> SAPI_PACKAGES = [
        "com.quantrix.scripting.core.sapi",
        "com.subx.scripting.core.sapi",
    ] as Set

    // Seed common scripting types explicitly so list-contained types such as
    // Formula do not depend on reachability from Model's return types.
    private static final List<String> ROOT_TYPES = [
        "com.quantrix.scripting.core.sapi.Model",
        "com.quantrix.scripting.core.sapi.Matrix",
        "com.quantrix.scripting.core.sapi.Category",
        "com.quantrix.scripting.core.sapi.Item",
        "com.quantrix.scripting.core.sapi.View",
        "com.quantrix.scripting.core.sapi.Cell",
        "com.quantrix.scripting.core.sapi.Formula",
    ]

    private static final Object CACHE_LOCK = new Object()
    private static Map cachedTypeCache = null
    private static Map cachedFunctionCache = null

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

        ROOT_TYPES.each { className ->
            try {
                def bt = BoundType.get(Class.forName(className))
                if (bt != null) queue << bt
            } catch (e) {
                // Skip optional or unavailable types.
            }
        }

        if (!queue) {
            println "[QxDocs] failed to seed scripting types"
            return cache
        }

        def seen = new HashSet<String>()
        while (queue) {
            def bt = queue.remove(0)
            if (bt == null) continue
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
                        if (ifBt != null && !seen.contains(iface.getName())) queue << ifBt
                    }
                }
            } catch (e) { /* skip */ }
        }

        println "[QxDocs] indexed ${cache.size()} types: ${cache.keySet().sort().join(', ')}"
        cache
    }

    private static Map getTypeCache() {
        synchronized (CACHE_LOCK) {
            if (cachedTypeCache == null) cachedTypeCache = buildTypeCache()
            cachedTypeCache
        }
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

    private static Map getFunctionCache() {
        synchronized (CACHE_LOCK) {
            if (cachedFunctionCache == null) cachedFunctionCache = buildFunctionCache()
            cachedFunctionCache
        }
    }

    // ── Document health helpers ────────────────────────────────────

    private static String textOrNull(value) {
        if (value == null) return null
        def text = value.toString().trim()
        text ? text : null
    }

    private static List collectProblemSummaries(document) {
        def pm = document?.getProblemManager()
        if (!pm) return []

        pm.refreshProblems()
        (pm.getProblems() ?: []).collect { p ->
            [
                description: textOrNull(p.getDescription()),
                level: textOrNull(p.getLevel()),
                location: textOrNull(p.getLocation()),
                fixes: ((p.getFixes() ?: []).collect { fix ->
                    textOrNull(fix.displayString()) ?: textOrNull(fix)
                }).findAll { it },
            ]
        }
    }

    private static List collectFormulaIssues(document) {
        def model = document?.getModel()
        if (!model) return []

        (model.getMatrices() ?: []).collectMany { matrix ->
            def matrixName = textOrNull(matrix.getName()) ?: matrix.toString()
            (matrix.getFormulae() ?: []).withIndex().collect { formula, index ->
                def error = textOrNull(formula.getErrorMessage())
                def warning = textOrNull(formula.getWarningString())
                def eclipse = textOrNull(formula.getEclipseString())

                def cycles = false
                try {
                    cycles = formula.isInvolvedInCycles()
                } catch (Exception ignored) {
                    cycles = false
                }

                if (!error && !warning && !eclipse && !cycles) return null

                [
                    matrix: matrixName,
                    formulaIndex: index,
                    formula: textOrNull(formula.getStringRepresentation()),
                    error: error,
                    warning: warning,
                    eclipse: eclipse,
                    cycles: cycles,
                ]
            }.findAll { it != null }
        }
    }

    // ── Public factory ─────────────────────────────────────────────

    static Expando build(document = null) {
        def typeCache = getTypeCache()
        def funcCache = getFunctionCache()

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

        // ── Document / model health ──

        api.problems = { ->
            if (!document) return [error: "ProblemManager is unavailable without a bound document"]
            try {
                collectProblemSummaries(document)
            } catch (Exception t) {
                [error: "Failed to collect document problems: ${t.message ?: t.class.name}"]
            }
        }

        api.warnings = { ->
            if (!document) return [error: "Document warnings are unavailable without a bound document"]

            try {
                def formulaIssues = collectFormulaIssues(document).collect { issue ->
                    issue + [source: "formula"]
                }
                def problems = collectProblemSummaries(document).collect { problem ->
                    problem + [source: "problem"]
                }

                formulaIssues + problems
            } catch (Exception t) {
                [error: "Failed to collect document warnings: ${t.message ?: t.class.name}"]
            }
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
            "Use api.types(), api.type(name), api.functions(), api.search(q), api.problems(), api.warnings()"
        }

        api
    }
}
