---
name: understanding-quantrix
description: Core concepts and syntax of Quantrix Modeler — the multi-dimensional spreadsheet. Use this skill when the user asks about Quantrix concepts (matrices, categories, items, views, formulas), formula syntax, name quoting rules, cell reference syntax, getSelection() string format, or pipe syntax (|...|). Also use when you need to understand Quantrix syntax while working with another Quantrix skill — for reading model files, building plugins, or controlling a live Quantrix session.
---

# Understanding Quantrix

Quantrix Modeler (https://quantrix.com) is a multi-dimensional spreadsheet. Unlike traditional row/column spreadsheets, Quantrix organises data along named dimensions (categories).

## Core concepts

**Matrices** are the primary data containers — analogous to sheets, but inherently multi-dimensional. Each matrix has its own categories, items, and formulas.

**Categories** are named dimensions (e.g. "Year", "Region", "Product"). Each category contains **items** — the individual members. Categories can be shared across matrices, creating automatic linkage.

**Items** can be organised hierarchically into **groups** (referenced via dot notation, e.g. `Expenses.Travel`). Groups support `summary()` aggregation in formulas.

**Views** are how matrices are displayed — table layouts, charts, canvas arrangements. A matrix can have multiple views. Views are referenced by name with `::` separating the view from the cell reference.

**Formulas** are per-matrix, written in Quantrix's own formula language (not Groovy). Ordered — evaluation priority runs top to bottom.

**Scripts** are Groovy code ("QGroovy" when extended with pipe syntax). Action scripts run manually or on events; function scripts are callable from formulas.

## Formula syntax

```
[In scope,] LHS = RHS [using mappings] [skip exclusions]
```

| Pattern | Meaning | Example |
|---------|---------|---------|
| `Item = expr` | Assignment | `Total = A * B` |
| `: = expr` | All-cells assignment (LHS `:` = every intersection) | `: = sumproduct(A::, B::)` |
| `Matrix::Item` | Cross-matrix ref (specific item) | `Assumptions::Rate` |
| `Matrix::Category` | Cross-matrix ref (entire category/dimension) | `Revenue::Time` |
| `Matrix::` | Cross-matrix ref (whole matrix) | `Revenue::` |
| `Item1:Item2` | Intersection | `Budget:Total` |
| `Cat[THIS]`, `[PREV]`, `[~PREV]` | Positional (`~` = soft) | `Year[~PREV]` |
| `Cat[FIRST]`, `[LAST]` | Endpoints | `Year[LAST-1]` |
| `In Item, expr` | Context scoping | `In Units, Q[THIS] = ...` |
| `skip A, B` | Exclude items | `= X * Y skip Total` |
| `A .. B` | Range | `sum(A .. Z)` |
| `summary(Group)` | Group children | `sum(summary(Costs))` |
| `using A as B` | Dimension mapping | `using S::Year as Year` |
| `select(val, keys, match)` | Filtered lookup | `select(S::Qty, S::Mo, @Mo)` |
| `sumproduct(a, b)` | Element-wise multiply + sum over matched dims (positional, not by identity — dimensions need same size, not same category) | `sumproduct(A::, B::)` |

Operators: `+` `-` `*` `/` `^` `&` `==` `<>` `<` `>` `<=` `>=` `#and#` `#or#` `#not#` `#true#` `#false#`

Comments: `//` line, `/* */` block.

Full function library and advanced patterns: [formulas.md](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/formulas.md).

### Formula scoping and eclipsing

**`In` scoping** is preferred when applying a formula to a subset of items. Compare:

- `In R1, : = sumproduct(A::, B::)` — clean, no warnings. Scopes the formula to R1, then `: =` assigns to all intersections within that scope.
- `R1 = sumproduct(A::, B::)` — works but warns *"Too few items from category Row specified on the left side"* because the RHS produces more dimensions than the LHS names.

**Eclipsing** occurs when two formulas have overlapping LHS targets without `skip` clauses. The later formula (higher index) wins for overlapping cells. The UI annotates this ("Eclipsed by 2" / "Eclipses 1"), but that metadata lives in internal desktop APIs such as `QFormula.getEclipseString()` and the document `ProblemManager`, not in the scripting wrapper's `errorMessage` or `warningMessage`.

### Formula diagnostics

Quantrix formula health shows up at several different layers:

| Layer | Typical surface | Catches |
|-------|------------------|---------|
| Parse error | Formula `errorMessage` / `QFormula.getErrorMessage()` | Syntax errors |
| Formula warning | Formula `warningMessage` / `QFormula.getWarningString()` | Circular references and other non-fatal warnings |
| Eclipse metadata | `QFormula.getEclipseString()` / ProblemManager | Overlapping formulas, eclipsed formulas, suggested fixes |
| Cell runtime error | Cell value like `#VALUE!` or `#REF!` | Dimension mismatches, bad refs, invalid calculations |

These layers are distinct. A formula can parse successfully, have no warning string, and still be eclipsed by another formula; likewise it can parse and warn cleanly but still produce runtime cell errors.

Use `In ... , : = ...` or explicit `skip` clauses when you want to scope a formula without overlapping broader formulas. For live inspection recipes, see the [managing-quantrix](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/managing-quantrix/SKILL.md) skill.

## Name quoting rules

Applies consistently across formulas, getSelection() strings, and pipe syntax.

**Unquoted names** may contain letters, digits (not first), and spaces. Must NOT contain:

```
! " # & ( ) * + , - . / ; < = > @ [ ] ^ { | } : \
```

Must not start with `$` or match keywords `skip`, `using`, `as` (case-insensitive).

**Quoted names** use single quotes, doubled for literal `'`:

```
'Long-term debt'           — hyphen
'2020'                     — starts with digit
'Shareholder''s Equity'    — apostrophe
'Name|Here'                — pipe character
```

## Cell reference syntax

- `::` separates **view/matrix name** from **coordinate**
- `:` separates **items from different categories**
- `..` defines a **range** between two coordinates
- `.` navigates **group hierarchy** (e.g. `Expenses.Travel`)

```
Matrix::ItemA:ItemB              — single cell
Matrix::A1:B1..A2:B2            — rectangular range
Matrix::*                        — all cells (no headers)
Matrix::^                        — all headers (no cells)
Matrix::Formula::1               — first formula
Matrix::                         — whole matrix (all dimensions)
Matrix::Category                 — a specific category/dimension within the matrix
```

In formulas, `Matrix::` and `Matrix::Category` are used for cross-matrix dimensional references. Quantrix auto-aligns on shared categories between the source and target matrices. `sumproduct()` multiplies element-wise and sums over dimensions not present in the target matrix, matching **positionally by size** — the contracted dimensions do not need to be the same (shared) category, just the same number of items. Using `Matrix::Category` explicitly names which dimension to operate on; `Matrix::` lets Quantrix infer it (works when unambiguous).

`getSelection()` accepts these strings:

```groovy
getSelection("Matrix::ItemA:ItemB").value
```

Object form — `matrix.getSelection(item, item, ...)` — takes `Item` objects directly, avoiding string building. Multiple items from the same category create a contiguous range (min to max by index).

## Pipe syntax (|...|)

In QGroovy scripts, pipe delimiters are shorthand for `getSelection()`:

```groovy
|Matrix::ItemA:ItemB|    →    getSelection("Matrix::ItemA:ItemB")
```

Recognised in code context only — not inside strings or comments. Cannot span lines. Opening `|` is disambiguated from bitwise OR using Groovy's regex-vs-division rules for `/`.

**Known limitation:** Quantrix's built-in pipe preprocessor (`AutoSelectionParser`) is a single regex that matches `|` inside strings and GString interpolation. Use `getSelection()` directly for reliable programmatic access.

## Formal grammars (EBNF)

Three EBNF grammars, inferred from Quantrix Modeler 24.4.0:

| Grammar | Scope |
|---------|-------|
| [name-and-reference.ebnf](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/name-and-reference.ebnf) | Names, quoting, coordinates, ranges, `::` resolution |
| [formula.ebnf](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/formula.ebnf) | Full formula language (superset of name-and-reference) |
| [pipe-selection.ebnf](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/pipe-selection.ebnf) | Pipe syntax within Groovy (subset of name-and-reference) |

Nesting is strict: valid pipe selection ⊂ valid reference ⊂ valid formula LHS. The [grammars.md](https://raw.githubusercontent.com/jahs/quantrix-tools/main/skills/understanding-quantrix/references/grammars.md) covers implementation details and known discrepancies between Quantrix's three internal parsers.

## Related skills

- **reading-quantrix-models** — Parse .model/.modelt files offline
- **building-quantrix-plugins** — Develop Groovy and Java plugins
- **managing-quantrix** — Control a live Quantrix session via REST API, inspect formula problems, and automate fixes
