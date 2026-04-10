# Quantrix Scripting Reference

Quantrix scripts are written in **Groovy 4** (as of Quantrix 2024). They live in named *script libraries* inside the model. Scripts come in two kinds: **action scripts** (buttons/menu items) and **function scripts** (callable from formulas).

## Script types

### Action scripts
Run from a button, canvas action, or script menu. Must implement:

```groovy
void perform()
{
    // your code
}

boolean enabled()
{
    return true   // whether the button/action is active
}
```

`enabled()` can inspect model state to conditionally disable the action:

```groovy
boolean enabled()
{
    def cell = |Matrix1::A1:B1|
    return cell.value != null && cell.value instanceof Number
}
```

### Function scripts (Function Sets)
Called directly from formulas (e.g., `Area = area(Radius)`). Declared as typed functions. Function sets are **pure calculations** — they cannot interact with Quantrix state (no pipe selections, no model manipulation). Parameters are restricted to `String`, `Date`, primitives, and arrays thereof.

```groovy
double area(double radius)
{
    return Math.PI * (radius * radius)
}

double circumference(double radius)
{
    return Math.PI * 2 * radius
}
```

Documentation annotations:
```groovy
@FunctionDoc(description="Returns the area of a circle.", argNames=["radius"], argDocs=["the radius"])
double area(double radius) { ... }

@MethodDoc(description="Expert mode method doc.", argNames=["param"], argDocs=["description"])
```

## Cell reference syntax (pipe syntax)

Cell references in scripts use `| ... |` delimiters:

| Syntax | What it selects |
|--------|-----------------|
| `\|Matrix1::\|` | All cells in Matrix1 |
| `\|Matrix1::A\|` | Category A (all items) |
| `\|Matrix1::A1\|` | Item A1 (nodes and cells) |
| `\|Matrix1::A1^\|` | Item A1, headers only |
| `\|Matrix1::A1*\|` | Item A1, cells only |
| `\|Matrix1::A1:B1\|` | Intersection of A1 and B1 |
| `\|Matrix1::A1:B1..A2:B2\|` | Range A1:B1 through A2:B2 |
| `\|Matrix1::<1,1>-<2,2>\|` | Numeric row/col index range |
| `\|Matrix1::Formula::1\|` | First formula in Matrix1 |
| `\|Matrix1::Formula::2-3\|` | Formulas 2 through 3 |
| `\|Matrix1::A{Descriptor}\|` | Item descriptor named "Descriptor" |
| `\|'Matrix1 - Chart1'::\|` | Chart1's chart grid |
| `\|'Matrix1 - Chart1'::Data::\|` | Chart1's table view |

## Script context (actions only)

Actions have implicit access to these context variables and methods:

```groovy
quantrix          // the Quantrix application instance
model             // the active model
matrix            // the current matrix

alert(message)                        // show dialog
ask(prompt, fields)                   // request user input (returns Map or null)
pause(milliseconds)                   // sleep
calculateNow()                        // force immediate recalculation
ensureCalculated()                    // ensure model is fully calculated
openPerspective(name)                 // switch canvas/perspective
closeAllViews()                       // close all open views
closeAllToolPanes()                   // close all tool panes
nextPresentation() / previousPresentation()  // navigate presentations
```

Code completion is available in the Quantrix script editor via CTRL+SPACEBAR.

## Reading and writing cell values

```groovy
// Single value
def val = |Matrix1::A1:B1|.value         // read
|Matrix1::A1:B1|.value = 42              // write scalar

// Multiple values (list, column-major order)
|Sales Projections::Year1:Q1:North|.values = [1200, 1400, 3500, 0]

// Cell properties
cell.value                                // cell contents (may be null)
cell.note                                 // cell annotation/note
cell.x / cell.y                          // coordinates (read-only)
cell.matrix                               // parent matrix reference

// Iterate items of a category
|Assumptions::Region|.children.each
{
    it.value = defaultRates[it.childIndex]   // it = Item object
}
```

## Formatting

```groovy
|Assumptions::Rate|.setToPercentNumberFormat()
|Sales Projections::|.setToCurrencyNumberFormat()
|Sales Projections::Year^|.backgroundColor = "#FFFF99"  // headers only
|Sales Projections::YTD|.backgroundColor = "#CCFFCC"
|Matrix1::|.backgroundColor = "none"                    // clear background
|Sales Projections::Quarter:|.columnWidth = 80
```

## Navigation and views

```groovy
openPerspective("Page 2")            // switch to named canvas/perspective
|Matrix1|.bringToFront()            // show a matrix
|Stage|.close()                      // hide a matrix
```

## User interaction

```groovy
// Simple message
alert("The value is even.")

// Text/number input
def input = ask("Enter a value", ["name": String, "count": Double.class])
if (input != null)
{
    def name = input.get("name")     // null if user canceled that field
    def count = input.get("count")
}

// Choice from a list
def label = "Choose a product"
def result = ask([(label): product.items.asList()])
if (result != null)
    def chosen = result[label]

// Multiple choices matching a selection list
def choiceMap = ask([(label): selections])
```

## Modifying model structure

```groovy
// Create a new matrix
def Matrix m = matrices.create("Sales Projections")

// Delete all categories in a matrix
m.categories.each { it.delete() }

// Create categories with items
m.categories.create("Product", ["Widget", "Gadget", "Gizmo", "Total"])
m.categories.create("Quarter", ["Q1", "Q2", "Q3", "Q4", "YTD"])

// Rename items
m.categories[0].items[0].name = "Rate"

// Delete extra category
m.categories[1].delete()

// Unlink a shared category (create independent copy)
sharedCategory.unlink()

// Group items
selection.group()

// Delete a matrix
matrices[0].delete()

// Rename a matrix
m.name = "Sales Projections"

// Create views
m.createChart()                           // new chart from matrix
matrices.create("name")                   // new matrix
presentations.create()                    // new canvas/presentation (called "presentations" in API)
```

## Managing category trays

```groovy
// Add to row/column/filter tray
|Sales Projections|.rowTrayCategories.add(|Sales Projections::Product|)
|Sales Projections|.columnTrayCategories.addAt(0, |Sales Projections::Year|)
|Sales Projections|.filterTrayCategories.add(product)

// Link a category from another matrix into this one
assumptionsMatrix.rowTrayCategories.add(|Sales Projections::Region|)

// Set filter item
|Sales Projections|.setFilterItem(|Sales Projections::Widget|)
```

## Adding and modifying formulas programmatically

```groovy
def formulae = |Sales Projections|.formulae

// Edit first formula
formulae[0].text = "Quarter[THIS] = Quarter[PREV] * Assumptions::Rate SKIP YTD, Total"

// Add more formulas
formulae.create("Q1:Year[THIS] = Q4:Year[PREV] * Assumptions::Rate SKIP Total",
                "YTD = sum(summary(Quarter)) SKIP Total",
                "Total = sum(summary(Product))")
```

Temporarily commenting out a formula is the standard circular-reference workaround:
```groovy
// Break the circular: comment out formula, recalculate, restore
formulae[9].text = "//'Pre-money price per share' = ..."
ensureCalculated()
formulae[9].text = "'Pre-money price per share' = ..."
ensureCalculated()
```

## Charts

```groovy
def Chart chart = |Sales Projections|.createChart()
chart.pointTrayCategories.add(|'Sales Projections - Chart1'::Year|)
chart.seriesTrayCategories.add(|'Sales Projections - Chart1'::Region|)
chart.filterTrayCategories.add(|'Sales Projections - Chart1'::Quarter|)
chart.bringToFront()

// Chart axis properties
chart.chartAxes["axisName"].minimum = 0       // or "Auto"
chart.chartAxes["axisName"].maximum = 100     // or "Auto"

// Legend item formatting
chart.legendItems.each {
    it.lineColor = "blue"
    it.lineThickness = 2
    it.dashPattern = "MediumDash"
}
```

## Exporting

```groovy
exportPDF().onlyViews("Certificate").export("certificate.pdf")
exportDelimitedText()     // CSV export
```

## Recalculation and data links

```groovy
ensureCalculated()        // ensure model is fully calculated
calculateNow()            // force immediate calculation (disabled by default during scripts)
updateAllDataLinks()      // refresh external data connections
updateSelectedImportMatrices(collection)  // update specific import matrices with dependency ordering
pushData()                // push data to external source
```

## Data import with credentials

```groovy
performWithEmbeddedCredentials { /* import operations */ }
performWithAuthentication("user", "pass") { /* import operations */ }
```

## Common patterns

### Add a new dimension item (e.g., "New Project" button)
```groovy
void perform()
{
    |Financial Schedule::Project|.children.create()
}
```

### Clear a form
```groovy
void perform()
{
    |Entry::Details:Entry|.values = ''
}
```

### Guard action with stage flag
```groovy
boolean enabled()
{
    return |Stage| != null && |Stage::Number|.value == 2
}
```

### Iterate over all canvases and delete them
```groovy
(0..<presentations.size).each
{
    presentations[0].delete()
}
```

### Delete all matrices except the last
```groovy
(0..<(matrices.size - 1)).each
{
    matrices[0].delete()
}
```

### Ask for input and use it
```groovy
def answer = ask("Enter your name", ["name": String, "filename": String])
if (answer.name != null && answer.filename != null)
{
    |Name::|.value = answer.name
    if (!answer.filename.endsWith(".pdf"))
        answer.filename += ".pdf"
    exportPDF().onlyViews("Certificate").export(answer.filename)
    alert("Exported: " + answer.filename)
}
```

### Dynamic page navigation with counter
```groovy
// Next
openPerspective("Page " + (int) ++|Navigator|.value)

// Previous
openPerspective("Page " + (int) --|Navigator|.value)
```

## Script properties

```groovy
myScript.menuVisible = false      // hide from Scripting Menu
myScript.name = "New Name"        // rename (if canSetName() is true)
myScript.perform()                // call another script (cannot call self)
```

## Date/time in scripts

Scripts support Java date types directly as cell values:
- `LocalDate`, `LocalTime`, `LocalDateTime`
- `java.util.Date`, `Calendar`

```groovy
|Matrix::DateCell|.value = LocalDate.now()
```

## External libraries

Place JAR files in the ScriptingLibs directory to make them available:
- macOS: `/Applications/Quantrix Modeler.app/Contents/java/app/ScriptingLibs`
- Windows: `C:\Program Files\Quantrix Modeler\ScriptingLibs`

Only JARs in this directory are accessible. Scripts run under a restricted Java security policy — no file system or network access.

## Debugging

- **Scripting Console** (CTRL+SHIFT+C): experimental code execution with model context
- **Output Console** (CTRL+SHIFT+O): diagnostic output via `System.out.println()`

## Qloud limitations

When running on Quantrix Qloud (web/server), scripts cannot:
- Show `alert()` or `ask()` dialogs
- Use UI methods (`bringToFront`, `close`)
- Access file system or third-party libraries
- Affect layout in other users' views

## Groovy notes

Scripts run in Groovy 4. Key things that differ from plain Java:
- `def` for untyped variables: `def result = someCall()`
- Typed variables optional: `def Matrix m = matrices.create(...)`
- Closures: `list.each { it.doSomething() }`
- String GStrings: `"Hello ${name}"`
- `0..<n` is an exclusive range (0 to n-1), used with `.each` for loops
- Null safety: check `if (result != null)` before accessing fields
- `instanceof` for type checking: `cell.value instanceof Number`

Standard Java libraries (`Math`, `String`, etc.) are available directly.
