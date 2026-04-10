---
name: managing-quantrix
description: Control a running Quantrix Modeler desktop app via the Groovy Server Plugin. Send QGroovy scripts to read/write cells, manage formulas, inspect structure, and automate model changes. Trigger when the user wants to interact with a live Quantrix session, automate model changes, or build agent workflows against Quantrix.
---

# Managing a Live Quantrix Session

The CLI is at `scripts/qxctl.py` (referred to as `qxctl` below).

`qxctl` talks to the Groovy Server Plugin running inside Quantrix Modeler on localhost. The primary workflow is sending QGroovy scripts to the sandboxed eval endpoint.

For Quantrix concepts, formula syntax, and name quoting rules, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill.

## Prerequisites

1. **Groovy Loader Plugin** installed in Quantrix's plugins dir
2. **Groovy Server Plugin** in the groovy-plugins dir
3. Quantrix Modeler running with a model open

The server auto-starts on `127.0.0.1:8182` when Quantrix launches.

## Launching Quantrix (macOS)

```bash
open -a "Quantrix Modeler"              # launch app
open -a "Quantrix Modeler" /path/to.model   # open a specific model
```

## Quick start

```bash
# Check connection
qxctl status

# List open models
qxctl models

# Run a script (heredoc — recommended for anything non-trivial)
qxctl eval << 'EOF'
matrices.collect { it.name }
EOF

# Run a one-liner
qxctl eval 'matrices.collect { it.name }'
```

## The eval command

`qxctl eval` is the primary tool. It sends a QGroovy script to Quantrix's sandboxed scripting context, which provides:

- `model`, `matrices` — the open model and its matrices
- `getSelection()` — cell/range access by name
- Pipe syntax `|Matrix::Item:Item|` — shorthand for `getSelection()`
- `api` — scripting API introspection plus model-health helpers such as `api.problems()` and `api.warnings()`
- Undo wrapping — the entire script is one undoable action
- All Quantrix scripting API functions

**Always use a heredoc** for multi-line scripts. The single-quoted delimiter (`'EOF'`) prevents shell expansion, so Groovy's `${}`, closures, and pipe syntax pass through unchanged:

```bash
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
m.categories.collect { cat ->
    [name: cat.name, items: cat.items.collect { it.name }]
}
EOF
```

### API introspection (`api` object)

The sandbox injects an `api` helper for exploring the public scripting API and formula function catalogue:

```bash
# Browse exposed scripting types
qxctl eval 'api.types()'
qxctl eval 'api.type("Matrix")'
qxctl eval 'api.member("Matrix", "formulae")'
qxctl eval 'api.resolve("model.matrices.getAt.categories")'

# Browse formula functions
qxctl eval 'api.categories()'
qxctl eval 'api.functions("Financial")'
qxctl eval 'api.function("IF")'

# Search both types and functions
qxctl eval 'api.search("formula")'
qxctl eval 'api.search("warning")'
```

Use `api` first when you need to discover available methods or formula functions. It is usually faster and more reliable than probing with metaclass tricks inside the sandbox.

### Known scripting gaps

For Quantrix formula semantics such as eclipsing, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill. Operationally, the main gaps to keep in mind are:

- Formula overlap / eclipsing is not surfaced by the sandboxed formula wrapper's `warningMessage`; use `api.warnings()`, `api.problems()`, or `eval-unsafe`.
- `api.warnings()` and `api.problems()` work by calling internal desktop APIs behind the scenes, not by reading extra fields from the sandbox wrapper.
- Formula diagnostics are split across layers: parse errors and simple warnings come from the scripting wrapper, while eclipse metadata and UI-style problems come from internal desktop APIs.
- Java reflection and some metaclass-based discovery patterns are unreliable or blocked in the sandbox. Prefer `api.type()`, `api.member()`, `api.resolve()`, and `api.search()`.

## Common patterns

### Discover model structure

```bash
# List all matrices with their categories
qxctl eval << 'EOF'
matrices.collect { m ->
    [name: m.name, categories: m.categories.collect { it.name }]
}
EOF

# List items in a category
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
def cat = m.categories.getAt("Time")
cat.items.collect { it.name }
EOF
```

### Read cell values

Pipe syntax is the most readable way to access cells:

```bash
# Single cell
qxctl eval '|Revenue::Q1:Net Revenue|.value'

# Multiple cells
qxctl eval << 'EOF'
[|Revenue::Q1:Net Revenue|.value,
 |Revenue::Q2:Net Revenue|.value,
 |Revenue::Q3:Net Revenue|.value]
EOF
```

Or use `getSelection()` directly for dynamic access:

```bash
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
def timeCat = m.categories.getAt("Time")
def rev = m.categories.getAt("Accounts").items.getAt("Revenue")
timeCat.items.collect { t ->
    [time: t.name, val: m.getSelection(t, rev).value]
}
EOF
```

Quote names containing special characters with single quotes:

```bash
qxctl eval '|Balance Sheet::'"'"'Long-term debt'"'"':2020|.value'

# Easier with a heredoc:
qxctl eval << 'EOF'
getSelection("Balance Sheet::'Long-term debt':'2020'").value
EOF
```

### Write cell values

```bash
# Set a single cell (cells covered by formulas cannot be written directly)
qxctl eval << 'EOF'
def sel = |Revenue::Q1:Units|
sel.value = 1500
EOF

# Write multiple cells
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
def units = m.categories.getAt("Accounts").items.getAt("Units")
def timeCat = m.categories.getAt("Time")
[["Q1", 1500], ["Q2", 1800], ["Q3", 2100]].each { q, v ->
    m.getSelection(timeCat.items.getAt(q), units).value = v
}
EOF
```

### Formulas

```bash
# List all formulas in a matrix
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
m.formulae.collect { [text: it.text] }
EOF

# Add a formula
qxctl eval << 'EOF'
matrices.getAt("Revenue").formulae.create("Profit = Revenue - Costs")
EOF

# Modify a formula
qxctl eval << 'EOF'
def f = matrices.getAt("Revenue").formulae.getAt(0)
f.text = "Total = Units * Price"
EOF
```

### Formula diagnostics

For the conceptual differences between parse errors, warnings, eclipsing, and cell runtime errors, read the [understanding-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/SKILL.md) skill. In a live session, these are the quickest checks:

```bash
# Check the sandboxed formula wrappers
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
m.formulae.collect { f ->
    [text: f.text, error: f.errorMessage, warning: f.warningMessage]
}
EOF
# Document-level problems shown in the UI, including overlap quick fixes
# (wrapped from the internal ProblemManager by api.problems())
qxctl eval 'api.problems()'

# Model-wide health: internal QFormula diagnostics plus ProblemManager issues
qxctl eval 'api.warnings()'

# Focus on eclipsed/eclipsing formulas only
qxctl eval << 'EOF'
api.warnings().findAll { it.source == "formula" && it.eclipse }
EOF
```

**Key formula object methods:** `text` (get/set), `errorMessage` (read-only), `warningMessage` (read-only), `delete()`, `deleteWithoutClearing()`.

Runtime cell errors such as `#VALUE!` and `#REF!` still need cell inspection. Spot-check them separately when a formula parses cleanly but computed cells look wrong:

```bash
qxctl eval << 'EOF'
def m = matrices.getAt("Result")
def cellErrors = []
def cats = m.categories.asList()
if (cats.size() >= 2) {
    cats[0].items.each { a ->
        cats[1].items.each { b ->
            def v = m.getSelection(a, b).value
            if (v instanceof String)
                cellErrors << [cell: "${a.name}:${b.name}", error: v]
        }
    }
}
cellErrors
EOF
```

### Categories and items

```bash
# Create a matrix with categories
qxctl eval << 'EOF'
def m = matrices.create("Projections")
m.categories.create("Year", ["2024", "2025", "2026"])
m.categories.create("Metric", ["Revenue", "Costs", "Profit"])
m.formulae.create("Profit = Revenue - Costs")
m
EOF

# Add an item to an existing category
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
m.categories.getAt("Time").items.create("Q5")
EOF
```

### Recalculation

Scripts are undo-wrapped but do NOT auto-recalculate. Force recalc after writes if needed:

```bash
qxctl eval << 'EOF'
|Revenue::Q1:Units|.value = 1500
model.recalculate()
EOF
```

## Unsafe scripting

`qxctl eval-unsafe` runs raw Groovy outside the sandbox with full JDK access. No model context, no pipe syntax, no undo. The `quantrix` variable provides the app instance.

```bash
qxctl eval-unsafe 'System.getProperty("user.home")'

qxctl eval-unsafe << 'EOF'
def app = quantrix
app.openDocuments.collect { it.name }
EOF
```

Useful internal pattern when you need the raw document problem list without the sandbox helper:

```bash
qxctl eval-unsafe << 'EOF'
def doc = quantrix.openDocuments[0]
def pm = doc.getProblemManager()
pm.refreshProblems()
pm.getProblems().collect { p ->
    [description: p.getDescription(),
     level: p.getLevel().toString(),
     location: String.valueOf(p.getLocation()),
     fixes: p.getFixes().collect { it.displayString() }]
}
EOF
```

`api.problems()` and `api.warnings()` use the same internal document APIs under the hood, but keep that access encapsulated instead of exposing the raw document object inside sandboxed scripts.

## Multi-model support

If multiple models are open, specify which with `-m`:

```bash
qxctl models
qxctl -m "Revenue Model" eval 'matrices.collect { it.name }'
```

If only one model is open, `-m` is optional (auto-detected).

## Python library

```python
from qxctl import QxClient

qx = QxClient()  # auto-detects model if only one open

# Sandboxed scripting (pipe syntax works)
qx.eval('|Revenue::Q1:Net Revenue|.value')
qx.eval('matrices.collect { it.name }')
qx.eval('api.types()')
qx.eval('api.warnings()')

# Unsafe scripting
qx.eval_unsafe('System.getProperty("user.home")')

# Server info
qx.status()
qx.models()

# Plugin management
qx.plugins()
qx.reload_plugin("quantrix-server")
qx.reload_all()
```

## Plugin management

```bash
qxctl plugins                            # list loaded plugins
qxctl reload-plugin quantrix-server      # reload one plugin
qxctl reload-all                         # reload all (restarts server)
```

`reload-all` restarts the server itself. The response is sent before the reload begins. Poll `qxctl status` to confirm the server is back.

## Connection

Default: `http://127.0.0.1:8182`

Override via `--port PORT` / `--host HOST` flags or `QX_PORT` / `QX_HOST` environment variables.

## Output modes

- **TTY:** pretty-printed JSON
- **Pipe/redirect:** compact JSON (for `jq` or programmatic parsing)
- `--json` / `-j`: force JSON even on TTY
- `--raw`: raw response body only
- Non-zero exit code on errors

## CLI reference

| Command | Description |
|---------|-------------|
| `status` | Server health + model list |
| `models` | List open models |
| `eval [script]` | Execute QGroovy (sandboxed, pipe syntax, undo-wrapped) |
| `eval-unsafe [script]` | Execute raw Groovy (system-level, no sandbox) |
| `plugins` | List loaded groovy plugins |
| `reload-plugin <id>` | Reload a specific plugin |
| `reload-all` | Reload all plugins (restarts server) |

Scripts read from stdin if omitted — use heredoc or pipe from a file.

## Scripting API reference

- `references/scripts.md` — Local QGroovy scripting reference: action vs function scripts, pipe syntax, model manipulation, user interaction, export
- [Quantrix Scripting Guide](https://help.idbs.com/Quantrix/Modeler_Help/LATEST/en/quantrix-scripting.html)
- [Quantrix Scripting API Reference](https://quantrix.com/help/scripting/index.html)
