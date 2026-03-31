# Quantrix Reference and Formula Grammars

Formal EBNF grammars for Quantrix Modeler's cell reference and formula syntax, inferred from version 24.4.0.

These grammars are not documented anywhere in Quantrix's user-facing documentation. They are inferred from the implementation and verified against runtime behaviour.

## The three grammars

```
  ┌─────────────────────────────────────────────────────┐
  │               formula.ebnf                          │
  │                                                     │
  │   Formula structure: in/LHS = RHS/using/skip        │
  │   Recurrence: [FIRST], [LAST], [THIS±n], [~soft]    │
  │   Expressions: #or#, #and#, +, -, *, /, ^, &, ...   │
  │   Functions, literals, links, env vars              │
  │                                                     │
  │   ┌───────────────────────────────────────────────┐ │
  │   │         name-and-reference.ebnf               │ │
  │   │                                               │ │
  │   │   View :: Reference                           │ │
  │   │   Names: quoted ('') and unquoted             │ │
  │   │   Coordinates: Item:Item                      │ │
  │   │   Ranges: Start..End                          │ │
  │   │   Cell refs: <row,col>                        │ │
  │   │   Descriptors: {name}                         │ │
  │   │   Modifiers: *, ^                             │ │
  │   │                                               │ │
  │   │   ┌─────────────────────────────────────────┐ │ │
  │   │   │      pipe-selection.ebnf                │ │ │
  │   │   │                                         │ │ │
  │   │   │   Same as name-and-reference, plus:     │ │ │
  │   │   │   - Groovy context disambiguation       │ │ │
  │   │   │   - No recurrence, no expressions       │ │ │
  │   │   └─────────────────────────────────────────┘ │ │
  │   └───────────────────────────────────────────────┘ │
  └─────────────────────────────────────────────────────┘
```

The pipe-selection grammar is a subset of name-and-reference (it excludes recurrence, which requires evaluation context). The name-and-reference grammar is a subset of the formula language (it excludes expressions, functions, and formula structure). The nesting is strict — every valid pipe selection is a valid standalone reference, and every valid standalone reference is a valid formula LHS.

## Implementations

Quantrix implements this grammar three times, each with different scope, correctness, and features:

| Implementation | Grammar level | Parser type | Correctness |
|---|---|---|---|
| `FormulaParser` | Full formula | JavaCC-generated | Correct — formal grammar |
| `getRange()` / `getNodeRange()` / `NodeNameList` | Name + reference | Hand-written (indexOf, charAt) | Mostly correct, some edge cases |
| `AutoSelectionParser` | Pipe selection | Single regex | Fundamentally broken |

### FormulaParser (JavaCC)
**Package:** `com.quantrix.engine.internal.formula.parser`

The formula parser is generated from a JavaCC `.jj` grammar file. It correctly handles the full formula language including name quoting, recurrence, expressions, and all operator precedence. The `.jj` source is not distributed — only the generated Java classes are in the jar.

### getRange() / getNodeRange() / NodeNameList
**Package:** `com.quantrix.core.internal.view.spreadsheet` (AbstractProjection), `com.quantrix.engine.iapi` (NodeNameList, NameQuoter)

The selection resolution code reimplements the name-and-reference grammar using `indexOf`, `NameQuoter.unquotedIndexOf()`, regex patterns, and a hand-written character-level iterator (`NodeNameList.AbstractNodeNameIterator`). This code correctly handles most cases but uses a different parsing strategy than FormulaParser, leading to subtle differences.

### AutoSelectionParser
**Package:** `com.quantrix.scripting.core.internal`

A single regex (`\|(?!\s)(([^\|'\n]|('[^'\n]*'))*)\\|`) applied to raw source text. This approach is fundamentally incorrect — it cannot distinguish pipe delimiters from `|` inside string literals, comments, or GString interpolation. Names containing `|` are handled correctly via single quoting (the regex's `'[^'\n]*'` branch captures quoted regions), but pipes in non-pipe contexts (strings, comments) are erroneously matched.

## Known discrepancies

1. **Pipe `|` in names:** Quantrix handles `|` in names via single quoting — a name containing `|` must be quoted as `'Name|Here'`. This works correctly in the formula parser, the pipe regex (the `'[^'\n]*'` branch captures quoted regions), and our preprocessor.

2. **`*` and `^` modifiers:** Handled by `getNodeRange()` but not exposed in FormulaParser's expression-level range references.

3. **`<row,col>` cell coordinates:** Handled by `getRange()` via regex (`cCellRangePattern`) as a separate branch from name-based references. The FormulaParser does not have this form.

4. **`{descriptor}` syntax:** Handled by `getRange()` (string scanning) and by FormulaParser via `DimensionInfoTerm` (`@`), but with different syntax — `{name}` vs `@name`.

5. **Quoting:** `NameQuoter` and `NodeNameList` use `''` (doubled quote) to escape `'` within single-quoted names. FormulaParser handles this at the lexer level (`QUOTED_NAME_STATE`). Both produce the same result but via different mechanisms.

6. **String context:** AutoSelectionParser applies its regex to the entire source text, matching `|` inside string literals and comments. The corrected `SelectionPreprocessor` in `pipe-preprocessor/` fixes this by tracking Groovy's lexical context.

7. **Unquoted name terminators:** In standalone references, unquoted names are terminated by `:` and `.` only. In the formula language, the set of terminators is much larger (all operators and punctuation are terminators).

## Recommendation

The three implementations should be consolidated:

1. **Define the name-and-reference grammar formally** (as in these EBNF files) and implement it once as a reusable parser.

2. **FormulaParser should use that parser** for its name/coordinate productions, extending it with recurrence brackets.

3. **getSelection(String) should use that parser** instead of hand-written indexOf/charAt code.

4. **The pipe preprocessor should remain at the source level** (it must operate before Groovy parsing), but should emit calls to the shared parser's data structures rather than re-parseable strings.

## Sources

All grammars inferred from Quantrix Modeler 24.4.0:

| Class | Jar | Grammar contribution |
|---|---|---|
| `FormulaParser` | `com.quantrix.engine-24.4.0.jar` | Formula language structure and expressions |
| `FormulaParserConstants` | same | Token definitions |
| `FormulaParserTokenManager` | same | Lexer rules (lexer states, token patterns) |
| `NodeNameList` | same | Name parsing: quoted/unquoted, `:` and `.` splitting |
| `NameQuoter` | same | Quote detection, quoting, dequoting, unquoted search |
| `AbstractProjection` | `com.quantrix.core-24.4.0.jar` | getRange(), getNodeRange(), cell range pattern |
| `ProjectionSelection` | same | `..` range separator constant |
| `ModelImpl` | `com.quantrix.scripting.core-24.4.0.jar` | getSelection() — `::` view splitting |
| `HasProjection` | same | Selection type dispatch |
| `AutoSelectionParser` | same | Pipe regex pattern |
