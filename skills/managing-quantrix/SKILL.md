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

### Checking formula errors

After creating or modifying formulas, **always check for errors**. The UI shows "Syntax error" but the scripting API exposes this via `errorMessage` and `warningMessage` on each formula object:

```bash
# Check all formulas for errors/warnings
qxctl eval << 'EOF'
def m = matrices.getAt("Revenue")
m.formulae.collect { f ->
    [text: f.text, error: f.errorMessage, warning: f.warningMessage]
}
EOF
```

A formula with `errorMessage: null` and `warningMessage: ""` (or null) is valid.

**Key formula object methods:** `text` (get/set), `errorMessage` (read-only), `warningMessage` (read-only), `delete()`, `deleteWithoutClearing()`.

**Three levels of formula feedback — always check all three after creating or modifying formulas:**

| Level | Property | Catches | Example |
|-------|----------|---------|---------|
| Formula parse error | `errorMessage` | Syntax errors | `"Syntax error"` |
| Formula warning | `warningMessage` | Circular references, other warnings | `"This formula is involved in one or more circular references."` |
| Cell runtime error | `getValue()` returns a String | Dimension mismatches, bad refs, etc. | `"#VALUE!"`, `"#REF!"` |

A formula can pass all three (valid + computes), fail at parse (`errorMessage`), pass parse but warn (`warningMessage` for circular refs), or pass both but produce cell errors (`#VALUE!` etc.). Always check all levels:

```bash
# Check all three levels on a matrix
qxctl eval << 'EOF'
def m = matrices.getAt("Result")

// 1. Formula-level: parse errors and warnings
def formulaIssues = m.formulae.asList().collect { f ->
    [text: f.text, error: f.errorMessage, warning: f.warningMessage]
}.findAll { it.error || it.warning }

// 2. Cell-level: runtime errors (spot-check — adapt categories to your matrix)
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

[formulaIssues: formulaIssues ?: "None",
 cellErrors: cellErrors ?: "None"]
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

- [Quantrix Scripting Guide](https://help.idbs.com/Quantrix/Modeler_Help/LATEST/en/quantrix-scripting.html)
- [Quantrix Scripting API Reference](https://quantrix.com/help/scripting/index.html)
