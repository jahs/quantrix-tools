# Quantrix Desktop Java API Guide for Plugin Development

Verified against Quantrix Modeler 24.4 via runtime testing.

## 1. Plugin Framework (`com.subx.framework`)

### IPlugin Interface
```java
package com.subx.framework;
public interface IPlugin {
    void start() throws Exception;
    void stop() throws Exception;
    String getId();

    public static abstract class Adapter implements IPlugin {
        public void start() throws Exception {}
        public void stop() throws Exception {}
    }
}
```

### Plugin Registration
- Plugins are discovered via `META-INF/MANIFEST.MF` with `Subx-Id` attribute
- Extensions declared via `Extension.getExtensions(extensionPointName)`
- Framework singleton: `Framework.instance()`

---

## 2. Application & Document Access

### QModelDocumentApplication (Singleton)
```java
// Package: com.quantrix.core.api
QModelDocumentApplication app = (QModelDocumentApplication) QModelDocumentApplication.cFactory.getInstance();

List<QModelDocument> docs = app.getOpenDocuments();
int count = app.getOpenDocumentCount();
QModelDocument doc = app.findOpenDocument(name);
QModelDocument doc = app.createDocument();
QModelDocument doc = app.openDocument(file, checkAutoSave, getLock);
QModelDocument doc = app.readFromStream(inputStream);
QModelDocument doc = app.getDocumentForModel(qModel);
```

### QModelDocument (extends XDocument, IAdaptable)
```java
// Package: com.quantrix.core.api

// MODEL ACCESS
QModel getModel();

// VIEW MANAGEMENT
List<QDocumentView> getViews();             // explicit views only — see note below
QDocumentView getViewNamed(String name);
QDocumentView getViewWithUUID(String uuid);
void addDocumentView(QDocumentView view);
boolean removeDocumentView(QDocumentView view);
QModelingMatrixView getModelingMatrixViewFor(QMatrix matrix);  // auto-created per matrix
QProjectedView getViewForProjection(QProjection projection);

// CALCULATION
void recalc();                              // dirty cells only
void recalcAll();                           // all cells
boolean isAutoCalculationOn();
void setAutoCalculate(boolean on);
void ensureCalculated(String reason);

// UNDO/REDO
void doUndo();
void doRedo();
boolean canUndo();
boolean canRedo();
XUndoManager getUndoManager();

// DOCUMENT STATE
String getName();
void setName(String name);
File getFile();
boolean isDirty();
boolean save();
boolean isReadOnly();
void closeDocument();

// ROLES
List<XRole> getRoles();                     // typically [Modeler, User, Viewer]
XRole getModelerRole();
XRole getViewerRole();
XRole getUserRole();
```

**Note on views:** `getViews()` returns only explicitly created views (user-created in the UI or via `addDocumentView`). Each matrix also has an auto-created `QModelingMatrixView` accessible via `getModelingMatrixViewFor(matrix)` — these are NOT included in `getViews()`.

---

## 3. Model & Matrix Access

### QModel (`com.quantrix.engine.api`)
```java
// Accessed via: document.getModel()
List<? extends QMatrix> getMatrices();
QMatrix getMatrixNamed(String name);
QMatrix getMatrixByUUID(String uuid);
QMatrix createMatrix();
QMatrix createMatrix(String name);
void removeMatrix(QMatrix matrix);

// Named coordinate resolution (no row/col needed)
QNodeCoordinate getNodeCoordinate(String str);          // e.g. "Cash:2020"
QNodeRange getNodeRange(String str);                    // e.g. "Revenue..COGS"
QNode getNodeNamed(String str);                         // find any node by name

// Calculation
void recalc();
void recalcAll();
void waitForCalculationToComplete();
boolean isCalculating();
boolean isAutoCalculationOn();
Locale getLocale();
```

### QMatrix (`com.quantrix.engine.api`)
```java
// Identity
String getName();
void setName(String name);                              // inherited from IHasName.Mutable
String getUUID();

// Categories
List<? extends QCategory> getCategories();
QCategory getCategoryAt(int index);
QCategory getCategoryNamed(String name);                // find by name
QCategory createCategory();
void removeCategory(QCategory category);

// Formulas
List<? extends QFormula> getFormulae();
QFormula createFormula();
void addFormula(QFormula formula, int index);
void removeFormula(QFormula formula);

// Cell access by coordinate (preferred — no row/col)
QCell getCell(XCoordinate coordinate);
QNodeCoordinate getNodeCoordinate(String str);          // parse "Item1:Item2"
QNodeCoordinate getNodeCoordinate(XCoordinate coord);
QNodeCoordinate getNodeCoordinate(List<? extends QNode> nodes);  // from QItem list
QNodeRange getNodeRange(String str);
QNodeRange getNodeRange(QNode from, QNode to);
XRange getFullRange();                                  // all cells
SubxIterator<? extends QCell> getCellIterator();        // iterate all cells
QNode getNodeNamed(String str);

// Has QMatrix.StructureListener and QMatrix.LogicListener
```

### QFormula (`com.quantrix.engine.api`)
```java
String getStringRepresentation();
void setStringRepresentation(String formula);
String getErrorMessage();
boolean hasError();
int getErrorPosition();
String getWarningString();
boolean hasWarning();
String getEclipseString();                              // info/notices
boolean isInvolvedInCycles();
boolean calculates(XCoordinate coordinate);             // does this formula cover this cell?
QTerm getRightHandSide();
QFormulaLHSRange getLeftHandSide();

// Factory:
QFormula formula = (QFormula) QFormula.cFactory.create();
```

---

## 4. Cell, Category, Item, Node Hierarchy

### QCell (`com.quantrix.engine.api`)
```java
// Reading values
QValue getValue();                                      // from QHasValue.Single
XCoordinate.Mutable getLocation();                      // cell's coordinate
QMatrix getMatrix();
String getCellName();
QFormula getFormula();                                   // null if input cell
boolean isCalculated();                                  // true if covered by formula
boolean isInputCell();                                   // true if no formula (default: !isCalculated)
boolean isEmpty();
boolean isValid();

// Writing values (requires Modeler role session — see section 10)
boolean canSetValue();                                   // permission check (role-dependent)
void setValue(QValue value);
void setStringValue(String str);
void setNumberValue(double d);
void setDateValue(XDateTime dt);
void setEmptyValue();
void setErrorValue(String str);

// Dependencies
Collection<? extends QMatrixRange> getSuccessors();     // cells that depend on this
Collection<? extends QMatrixRange> getPredecessors();    // cells this depends on
```

### QValue (`com.quantrix.engine.api`, extends XTypedValue)
```java
// Type checking
boolean isEmpty();
boolean isError();
boolean isString();
boolean isNumber();
boolean isDate();

// Value access (from XTypedValue)
String getStringValue();
double getNumberValue();
XDateTime getDateValue();
String getErrorValue();
String getStringRepresentation();                       // formatted display string
```

### QCategory (`com.quantrix.engine.api`, extends QParentNode)
```java
String getName();
void setName(String name);                              // inherited from IHasName.Mutable
String getUUID();
List<? extends QItem> getItems();                       // all leaf items (flat, groups expanded)
List<? extends QMatrix> getMatrices();                  // matrices using this category
boolean isLinked();                                      // shared across matrices?

// Descriptors (metadata on items)
List<String> getDescriptors();
void addDescriptor(String name);
void removeDescriptor(String name);

// From QParentNode:
QNode getChildAt(int index);
int getChildCount();
QNode getFirstChild();
QNode getLastChild();
QItem createChildItem();
List<? extends QItem> createChildItems(int index, int count);
void removeChildNode(QNode node);
QGroup groupChildNodes(int from, int to);
void ungroupChildNodes(QGroup group);
void moveNode(QNode node, int toIndex);
```

### QItem (`com.quantrix.engine.api`, extends QNode)
```java
int getItemIndex();                                     // position in category's flat item list
String getDescriptorValue(String descriptorName);
void setDescriptorValue(String descriptorName, String value);
```

### QNode (`com.quantrix.engine.api`)
```java
String getName();
String getEscapedName();                                // quoted if needed for range strings
String getMinimalName(boolean includeMatrixContext);
String getFullName(boolean includeMatrixContext);        // group-qualified: "Current Assets.Cash"
QCategory getCategory();
QParentNode getParent();
List<? extends QNode> getChildren();
boolean hasChildren();
int getChildIndex();                                    // position in parent
QNode getPreviousSibling();
QNode getNextSibling();
boolean isSummaryItem();
int getLevel();
int getItemCount();                                     // descendant leaf items
QItem getItemAt(int index);
QItem getFirstItem();
QItem getLastItem();
```

### QGroup (`com.quantrix.engine.api`, extends QParentNode, QNode)
```java
boolean canUngroup();
void ungroup();
// Plus all QParentNode methods for managing children
```

---

## 5. Named Coordinate Access (preferred over row/col)

The QMatrix API supports direct cell access by named coordinates. No projection or view required.

### QNodeCoordinate (`com.quantrix.engine.api`)
```java
boolean isValid();
QMatrix getMatrix();
QItem getItemForCategory(QCategory category);           // which item at this coordinate
XCoordinate.Mutable getCoordinate();
QCell getCell();                                        // the cell at this coordinate
QValue getValue();                                      // shortcut for getCell().getValue()
```

### QNodeRange (`com.quantrix.engine.api`)
```java
QMatrix getMatrix();
QCategoryRange getRangeForCategory(QCategory category);
XRange getCoordinateRange();
SubxIterator<? extends QCell> getCellIterator();        // iterate cells in range
SubxIterator<? extends QCell> getCellIterator(List<QCategory> categories);

// Factory:
QNodeRange.cFactory.create(QMatrix matrix, String name);
QNodeRange.cFactory.create(QNodeCoordinate from, QNodeCoordinate to);
QNodeRange.cFactory.create(QMatrix matrix, XRange range);
```

### QCategoryRange (`com.quantrix.engine.api`)
```java
QCategory getCategory();
QNode getFrom();
QNode getTo();
int getFromIndex();
int getToIndex();
boolean contains(int itemIndex);
boolean isConstrained();
Stream<QItem> streamItems();
```

### Practical Pattern: Read/Write Cell by Name
```java
QMatrix matrix = doc.getModel().getMatrixNamed("Balance Sheet");

// Build coordinate from category/item names
QCategory yearCat = matrix.getCategoryNamed("Year");
QCategory itemsCat = matrix.getCategoryNamed("Balance Sheet Items");
QItem yearItem = findItemByName(yearCat, "2020");
QItem bsItem = findItemByName(itemsCat, "Cash");

QNodeCoordinate coord = matrix.getNodeCoordinate(List.of(yearItem, bsItem));
QCell cell = coord.getCell();

// Read
QValue value = cell.getValue();
String str = value.getStringValue();
double num = value.getNumberValue();

// Write (requires Modeler session — see section 10)
cell.setStringValue("5000");
cell.setNumberValue(5000.0);

// Helper to find item by name in a category
static QItem findItemByName(QCategory cat, String name) {
    for (QItem item : cat.getItems()) {
        if (name.equals(item.getName()) || name.equals(item.getFullName(false))) {
            return item;
        }
    }
    return null;
}
```

### Alternative: String-based coordinate resolution
```java
// Uses Quantrix intersection syntax: Item1:Item2
QNodeCoordinate coord = matrix.getNodeCoordinate("Cash:2020");
QCell cell = coord.getCell();

// Range iteration
QNodeRange range = matrix.getNodeRange("Revenue..COGS");
SubxIterator<? extends QCell> iter = range.getCellIterator();
while (iter.hasNext()) {
    QCell c = iter.next();
    // ...
}
```

**Warning:** String-based resolution requires proper escaping (single quotes for special chars, doubled single quotes for literal quotes). The `QItem` list approach avoids this entirely.

---

## 6. QFullProjection - View-based Cell Access

The projection provides a 2D row/col grid view of a matrix. Only needed for view-specific operations (formatting, axis layout, filtering). For data access, prefer the named coordinate API in section 5.

```java
// Package: com.quantrix.core.api.grid
public interface QFullProjection extends QProjection {
    QCell getCellAt(int row, int column);
    QNodeCoordinate getNodeCoordinate(int row, int col);
    XCoordinate getCoordinateAt(int row, int column);
    String getFormattedValueAt(int row, int column, boolean includeFormat);
    void setFormattedValueAt(String value, int row, int column, boolean parseFormula);
    boolean canSetCellValue(int row, int column);
    boolean isCellCalculated(int row, int column);
    RowColumn getCellForLocation(XCoordinate coordinate);
    QNodeRange getNodeRange(String name);
}
```

### Axis APIs
```java
// QProjectionRowColumnAxis (parent of QRowAxis, QColumnAxis)
int nonCollapsedItemCount();
XCoordinate getCoordinateAtOffset(int offset);
QAxisNode<? extends QItem> getAxisNodeAtOffset(int offset);
String getFullNameAt(int offset);

// QCategoryAxis
QCategory getCategory(int index);
QCategory leafCategory();
QCategory topCategory();
int indexOf(QCategory category);
boolean hasCategory(QCategory category);
int getLeafItemCount();
List<? extends QItem> getItemsAtOffset(int offset);
int getOffsetForCoordinate(XCoordinate coordinate);
```

---

## 7. Undo Manager

### XUndoManager (`com.subx.general.core.api.change`)
```java
String getUndoName();
String getRedoName();
boolean canUndo();
boolean canRedo();
void undo();
void redo();
void begin(String label);
void commit();
void rollback();
void clear();
<T> T perform(String label, IOperation<T> op);          // preferred — auto commit/rollback
<T> T performWithoutUndo(IOperation<T> op);
```

### Usage Pattern
```java
// Preferred: use perform() for automatic commit/rollback
XUndoManager undo = doc.getUndoManager();
undo.perform("My Operation", () -> {
    // ... make changes ...
    return null;
});
```

---

## 8. Roles & Permissions

Quantrix uses a role-based permission system. Every mutation (cell writes, structural changes) checks permissions against the current session's role.

### XRole (`com.subx.general.core.api.role`)
```java
void setPassword(String password);
boolean hasPassword();
XSession login(String password);                        // null for no password
IGrantContext getContext();
boolean can(XGrant<?, ?> grant);
void grant(XGrant<?, ?> grant);
void revoke(XGrant<?, ?> grant);
Set<XGrant<?, ?>> getGrants();
```

### XSession (`com.subx.general.core.api.role`)
```java
XRole getRole();
boolean isAuthorized(XGrant<?, ?> grant);
void assertGranted(XGrant<?, ?> grant);
<T> T performOperation(IOperation<T> op);               // run code with this role's permissions
void run(Runnable runnable);                            // void variant
```

### Default Roles
Documents have three roles by default: **Modeler** (full access), **User** (limited edits), **Viewer** (read-only). In the desktop app, the UI typically operates under the Modeler role, but plugins start with the Default Role which lacks structural grants.

### Running Code as Modeler (critical for plugins)
```java
// Without this, cell writes and structural mutations fail with PermissionException:
// "Cannot set value on cell Matrix @ <x, y>"
// "Default Role does not have required grant: .structure.formulae.Matrix"

XRole modelerRole = doc.getModelerRole();
XSession session = modelerRole.login(null);  // no password in desktop mode
session.performOperation(() -> {
    XUndoManager undo = doc.getUndoManager();
    return undo.perform("My Operation", () -> {
        cell.setStringValue("5000");
        return null;
    });
});
```

### Permission System Internals
The `com.subx.general.core.internal.Permission` class checks a static `sSuppressPermissions` flag controlled by the system property `subx.core.suppressPermissionChecks`. Setting `-Dsubx.core.suppressPermissionChecks=true` in JVM args disables all permission checks globally — useful for debugging but not recommended for production.

---

## 9. Selection & UI Context

The selection system lives in the UI layer, separate from the document/model layer.

### Getting the Current Selection from a Plugin
```java
// 1. Get the UI application singleton
DocumentUIApplication uiApp = DocumentUIApplication.runningInstance();
// Package: com.subx.document.ui.iapi

// 2. Get the frame for a specific document
XDocumentFrame frame = uiApp.getUIForDocument(doc, false);
// Package: com.subx.document.ui.api

// 3. Get the action context (carries current view and selection state)
IActionContext ctx = frame.getActionContext();

// 4. Get the current selection
IHasSelection hasSel = ctx.getInterface(IHasSelection.class);
ISelection sel = hasSel.getSelection();
String description = sel.getDescription();  // e.g. "Cash", "Revenue..COGS"

// 5. Get the current view
QDocumentView view = ctx.getInterface(QDocumentView.class);
```

### XDocumentFrame (`com.subx.document.ui.api`)
```java
XDocument getDocument();
IActionContext getActionContext();
JFrame getFrame();
String getDocumentName();
void toFront();
boolean isVisible();
```

### XDocumentApplicationUI (`com.subx.document.ui.api`)
```java
XDocumentFrame getLastActiveDocument();
JFrame getActiveDocument();
int getOpenDocumentWindowCount();
XDocumentFrame getOpenDocumentWindow(int index);
XDocumentFrame getUIForDocument(XDocument doc, boolean create);
boolean hasOpenDocument();
void endCurrentEdit();
```

### Selection Description Format
The `ISelection.getDescription()` returns the coordinate text shown in the status bar:
- Single cell: `"Cash"` or `"Other Assets.Deferred income taxes"` (group-qualified)
- Range: `"Quick Ratio..Cash Ratio"`
- Multi-category intersection: `"Cash:2020"`

---

## 10. IAdaptable Pattern

Many Quantrix interfaces extend `IAdaptable`, which provides a generic interface lookup:

```java
// Package: com.subx.general.core.api.adaptable
public interface IAdaptable {
    <T> T getInterface(Class<T> cls);
    Set<Class<?>> getAllInterfaces();
}
```

Used extensively to bridge between layers:
```java
// Get selection from action context
IHasSelection sel = actionContext.getInterface(IHasSelection.class);

// Get view from action context
QDocumentView view = actionContext.getInterface(QDocumentView.class);

// Get interfaces from document
QModel model = doc.getInterface(QModel.class);
```

---

## 11. Key Access Patterns (Tested & Verified)

### Read Cell by Named Coordinates
```java
QMatrix matrix = doc.getModel().getMatrixNamed("Balance Sheet");
QCategory yearCat = matrix.getCategoryNamed("Year");
QCategory itemsCat = matrix.getCategoryNamed("Balance Sheet Items");

// Find items
QItem year2020 = null;
for (QItem item : yearCat.getItems()) {
    if ("2020".equals(item.getName())) { year2020 = item; break; }
}
QItem cash = null;
for (QItem item : itemsCat.getItems()) {
    if ("Cash".equals(item.getName())) { cash = item; break; }
}

// Get cell and read value
QNodeCoordinate coord = matrix.getNodeCoordinate(List.of(year2020, cash));
QCell cell = coord.getCell();
QValue value = cell.getValue();
// value.getNumberValue() → 399.0
// value.isCalculated() → false
```

### Write Cell with Modeler Permissions
```java
XRole modelerRole = doc.getModelerRole();
XSession session = modelerRole.login(null);
session.performOperation(() -> {
    XUndoManager undo = doc.getUndoManager();
    return undo.perform("Write Cell", () -> {
        cell.setStringValue("5000");
        return null;
    });
});
```

### Create Matrix with Modeler Permissions
```java
XRole modelerRole = doc.getModelerRole();
XSession session = modelerRole.login(null);
session.performOperation(() -> {
    XUndoManager undo = doc.getUndoManager();
    return undo.perform("Create Matrix", () -> {
        QMatrix m = model.createMatrix();
        m.setName("My Matrix");
        return null;
    });
});
```

### Get Current UI Selection
```java
// Must run on EDT
DocumentUIApplication uiApp = DocumentUIApplication.runningInstance();
XDocumentFrame frame = uiApp.getUIForDocument(doc, false);
IActionContext ctx = frame.getActionContext();
IHasSelection hasSel = ctx.getInterface(IHasSelection.class);
if (hasSel != null) {
    ISelection sel = hasSel.getSelection();
    if (sel != null && !sel.isEmpty()) {
        String desc = sel.getDescription();  // "Other Assets.Deferred income taxes"
    }
}
QDocumentView view = ctx.getInterface(QDocumentView.class);  // currently focused view
```

### Iterate All Cells in a Matrix
```java
SubxIterator<? extends QCell> iter = matrix.getCellIterator();
while (iter.hasNext()) {
    QCell cell = iter.next();
    QValue value = cell.getValue();
    QNodeCoordinate nc = matrix.getNodeCoordinate(cell.getLocation());
    // Get item for each category
    for (QCategory cat : matrix.getCategories()) {
        QItem item = nc.getItemForCategory(cat);
        String itemName = item.getName();
    }
}
```

### Discover Matrix Structure
```java
for (QCategory cat : matrix.getCategories()) {
    System.out.println("Category: " + cat.getName());
    for (QItem item : cat.getItems()) {
        System.out.println("  " + item.getName()
            + " (full: " + item.getFullName(false)
            + ", index: " + item.getItemIndex() + ")");
    }
}
```

---

## 12. Key Package Summary

| Package | JAR | Key Classes |
|---------|-----|-------------|
| `com.subx.framework` | subx-framework | `IPlugin`, `Framework`, `Extension` |
| `com.subx.document.core.api` | subx-document-core | `XDocument`, `XDocumentApplication` |
| `com.subx.document.ui.api` | subx-document-ui | `XDocumentFrame`, `XDocumentApplicationUI` |
| `com.subx.document.ui.iapi` | subx-document-ui | `DocumentUIApplication` (singleton via `runningInstance()`) |
| `com.subx.general.core.api.change` | subx-general-core | `XUndoManager`, `IOperation` |
| `com.subx.general.core.api.role` | subx-general-core | `XRole`, `XSession`, `XGrant` |
| `com.subx.general.core.api.adaptable` | subx-general-core | `IAdaptable` |
| `com.subx.general.ui.api.selection` | subx-general-ui | `ISelection`, `IHasSelection`, `ISelectionManager` |
| `com.subx.general.ui.api.actions` | subx-general-ui | `AbstractContextAction`, `IActionContext` |
| `com.quantrix.core.api` | quantrix-core | `QModelDocument`, `QModelDocumentApplication` |
| `com.quantrix.core.api.view` | quantrix-core | `QDocumentView`, `QMatrixView`, `QModelingMatrixView` |
| `com.quantrix.core.api.grid` | quantrix-core | `QFullProjection`, `QRowAxis`, `QColumnAxis`, `QCategoryAxis`, `QAxisNode`, `RowColumn` |
| `com.quantrix.core.api.grid.range` | quantrix-core | `QFullProjectionCellRange`, `QFullProjectionNodeRange` |
| `com.quantrix.engine.api` | quantrix-engine | `QModel`, `QMatrix`, `QFormula`, `QCategory`, `QItem`, `QNode`, `QGroup`, `QCell`, `QValue`, `QNodeCoordinate`, `QNodeRange`, `QCategoryRange` |
| `com.quantrix.scripting.core.sapi` | quantrix-scripting-core | `Model`, `Matrix`, `Category`, `Item`, `Formula`, `View`, `Cell` (scripting wrappers) |
| `com.quantrix.scripting.core.sapi.impl` | quantrix-scripting-core | `ModelImpl` (entry point: `new ModelImpl(doc)`) |

---

## 13. Common Pitfalls

1. **Permissions:** Plugin code runs under the Default Role which lacks grants for cell writes and structural mutations. Always use `doc.getModelerRole().login(null).performOperation(...)` for mutations.

2. **EDT requirement:** All Quantrix API calls must run on the Swing Event Dispatch Thread. Use `SwingUtilities.invokeLater()` or a wrapper like `EdtExecutor`.

3. **`getViews()` is incomplete:** `doc.getViews()` only returns explicit views. Use `doc.getModelingMatrixViewFor(matrix)` to get the auto-created modeling view for each matrix.

4. **`setName` not `forceName`:** The method is `setName(String)` inherited from `IHasName.Mutable`. There is no `forceName` method on QMatrix or QCategory.

5. **`canSetValue()` is role-dependent:** `QCell.canSetValue()` checks both formula coverage AND role permissions. Use `cell.isCalculated()` to check only formula coverage. Use `cell.isInputCell()` as the inverse.

6. **Generic wildcards:** Use `List<? extends QMatrix>` not `List<QMatrix>` for return types from `getMatrices()`, `getCategories()`, `getItems()`, etc.

7. **SubxIterator:** `QMatrix.getCellIterator()` and `QNodeRange.getCellIterator()` return `SubxIterator<? extends QCell>` (package `com.subx.general.core.api.collection`), which extends both `Iterator` and `Iterable`.

8. **Named coordinates avoid escaping:** Prefer building coordinates via `matrix.getNodeCoordinate(List.of(item1, item2))` over string parsing with `matrix.getNodeCoordinate("Item1:Item2")`. The list approach bypasses all quoting/escaping rules.

9. **Use sapi for structural mutations:** `QModel.createMatrix()` / `removeMatrix()` does NOT update the browser tree — undo leaves ghost entries. Use the scripting API (`ModelImpl.getMatrices().create/delete`) instead. It handles browser tree, undo, and all side effects correctly. Same applies to `QModelDocument.save()` — use `ModelImpl.save()` or wrap in a Modeler session.
