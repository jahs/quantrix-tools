# Quantrix Model XML Schema Reference

Comprehensive reference for all XML elements in Quantrix `.model` and `.modelt` files. Organized by structural area.

## Table of Contents

1. [Document Root](#1-document-root)
2. [Model and Tables](#2-model-and-tables)
3. [Categories and Items](#3-categories-and-items)
4. [Formulas](#4-formulas)
5. [Cell Data and Constraints](#5-cell-data-and-constraints)
6. [Time Dimensions](#6-time-dimensions)
7. [Model Browser and Views](#7-model-browser-and-views)
8. [View Axes and Projections](#8-view-axes-and-projections)
9. [Canvas and Presentation Objects](#9-canvas-and-presentation-objects)
10. [Charts](#10-charts)
11. [Formatting System](#11-formatting-system)
12. [Scripts](#12-scripts)
13. [Data Sources](#13-data-sources)
14. [Roles and Permissions](#14-roles-and-permissions)
15. [Property Store](#15-property-store)
16. [Headers and Footers](#16-headers-and-footers)
17. [Images](#17-images)
18. [Perspectives](#18-perspectives)
19. [Complete Factory-ID Index](#19-complete-factory-id-index)

---

## 1. Document Root

```xml
<document oid="0">
  <version>21200</version>           <!-- format version number -->
  <locale>enUS</locale>              <!-- locale without separator -->
  <model oid="1">...</model>
  <headers count="N">...</headers>
  <footers count="N">...</footers>
  <model-browser oid="N">...</model-browser>
  <property-store>...</property-store>
  <modelerRole oid="N">...</modelerRole>
  <roles count="3">...</roles>
  <userRole ref="N"/>
  <viewerRole ref="N"/>
  <needs-calc>false</needs-calc>
  <appFeatures/>
  <ignore-all-filters>false</ignore-all-filters>  <!-- optional -->
</document>
```

---

## 2. Model and Tables

```xml
<model oid="1">
  <tables count="N">
    <table oid="N">
      <hidden>true</hidden>              <!-- optional: hidden helper table -->
      <name>Table Name</name>
      <uuid>...</uuid>
      <categories count="N">...</categories>
      <formula-list count="N">...</formula-list>
      <data size="N">values...</data>
      <cell-constraints>...</cell-constraints>
      <invalid-cells>0</invalid-cells>
      <sticky>true</sticky>              <!-- optional: slicer table -->
      <time-configuration ref="N"/>      <!-- optional: links to time advisor parent -->
    </table>
  </tables>
  <linked-tables/>                       <!-- for external/remote linked tables -->
  <audit-trail>
    <entries/>
    <auditing>false</auditing>
    <max-entries>600</max-entries>
  </audit-trail>
  <max-cycle-iterations>1</max-cycle-iterations>
  <min-cycle-delta>0.001</min-cycle-delta>
  <calculation-strategy>DEFAULT</calculation-strategy>
  <structure-version>N</structure-version>
  <property-store>...</property-store>   <!-- scripts, data sources -->
</model>
```

---

## 3. Categories and Items

```xml
<category oid="N">
  <name>Category Name</name>
  <children count="N">
    <child oid="N" factory-id="item">
      <name>Item Name</name>
      <summary-type>sum</summary-type>          <!-- optional: summary item -->
      <property-store>...</property-store>       <!-- optional: aggregation metadata -->
      <descriptor-values>                        <!-- optional -->
        <descriptor>Summarization Method</descriptor>
        <value>Average of Price</value>
      </descriptor-values>
    </child>
    <child oid="N" factory-id="group">
      <name>Group Name</name>
      <children count="N">
        <!-- nested items and groups -->
      </children>
    </child>
  </children>
  <uuid>...</uuid>
  <id>N</id>                                    <!-- integer category ID -->
  <descriptors count="N">                        <!-- optional -->
    <descriptor>Descriptor Name</descriptor>
  </descriptors>
  <descriptor-expressions/>
  <advisor oid="N" factory-id="month">...</advisor>  <!-- optional: time config -->
  <property-store>...</property-store>               <!-- optional: aggregation -->
</category>
```

**Category advisor types:**
- Time advisors: `factory-id="year|quarter|month|other"` — generates time-series items (see [Time Dimensions](#6-time-dimensions))
- `factory-id="com.quantrix.engine.CellRangeCategoryItemGenerator"` — generates items dynamically from a cell range in another matrix; contains `<ranges>` with `<range>` elements pointing to source matrices

**Child `factory-id` values:**
- `item` — leaf category item (row/column header)
- `group` — grouping container with nested children

**Shared categories:** When a table uses `<category ref="N"/>` instead of defining a new category, it shares that dimension with whichever table originally defined it. Changes to shared categories (adding items, reordering) affect all tables that reference them.

**Disambiguating duplicate item names:** When items in different groups share the same name, use dot-qualified paths: `Current Assets.Deferred income taxes` vs `Other Liabilities.Deferred income taxes`.

---

## 4. Formulas

```xml
<formula-list count="N">
  <formula oid="N">
    <formula-string>Item = expression</formula-string>
    <uuid>...</uuid>
  </formula>
</formula-list>
```

### Complete Formula Syntax Reference

**Assignment:** `ItemName = expression`

**Cross-matrix reference:** `MatrixName::ItemName`
- Example: `Revenue = Sales Forecast::Revenue:Total skip YTD`

**Intersection qualifiers (colon-separated):**
- `Item1:Item2` — targets the cell at the intersection of items from different categories
- `Revenue:Total:Year1` — three-way intersection
- `Matrix::Item1:Item2` — cross-matrix with intersection

**Quoted names:** Single quotes for names containing special characters:
- `'Plus: non-deductible Goodwill Amort'`
- `'Marketing/unit'`
- `'2025'` — for items that look like numbers

**Positional references:**
- `Category[THIS]` — current position in iteration
- `Category[PREV]` — previous position
- `Category[FIRST]` — first item
- `Category[LAST]` — last item
- `Category[LAST-1]` — second-to-last item

**Context scoping:** `In ItemName, expression`
- `In Units, Quarter[THIS] = Quarter[PREV] * Growth`

**Exclusion:** `expression skip Item1, Item2, Item3`
- `Revenue = Units * Price skip YTD, Total, All Regions`

**Range aggregation:** `sum(ItemA .. ItemB)` — sums all items from A to B
- `Unlevered Free Cash Flow = sum(Net Income .. 'Less: CapEx')`

**Group summaries:** `sum(summary(GroupName))` — sums the summary children of a group
- `Total Current Assets = sum(summary(Current Assets))`

**Nested group paths:** `Group.SubGroup` — dot-separated path
- `Total Other Assets = sum(summary(Assets.Other Assets))`

**Cross-matrix dimension mapping:** `expression using SourceMatrix::Category as TargetCategory`
- `Quantity Mailed = sum(select(Sales Forecast::Quantity Mailed, Sales Forecast::Calendar Month, @Month)) using Sales Forecast::Calendar Year as Year`
- `Statement Item = Statement Builder:: using Statement Assumptions::Scenario as Statement Builder::Scenario`
- Maps items from one category to another during cross-matrix evaluation

**Filtered queries:** `select(value_expr, filter_category, match_value)`
- `select(Sales::Qty, Sales::Month, @Month)` — selects values where Month matches current @Month
- Variants: `selectlike`, `selectbetween`, `selectgreaterthan`, `selectlessthan`

**Operators:**
- Arithmetic: `+`, `-`, `*`, `/`
- Comparison: `=`, `<>`, `<`, `>`, `<=`, `>=`
- Logical: `and()`, `or()`, `not()`

For the complete list of 200+ available functions organized by category, see the official documentation: https://help.idbs.com/Quantrix/Modeler_Help/LATEST/en/quantrix-functions-by-type.html

**HTML entities:** `&quot;` for `"`, `&amp;` for `&`, `&lt;` for `<`, `&gt;` for `>`

---

## 5. Cell Data and Constraints

### Data Storage

```xml
<data size="1600">12.0,240000.0,180000.0,60000.0,...</data>
```

- `size` = product of all category item counts (flattened — groups count as one item per leaf)
- Values are comma-separated
- Numeric: `0.0`, `85.0`, `2.3231698119999997E7`
- String: prefixed with `'`: `'This is text`
- Empty: no value between commas: `,,`
- Escaped commas: `\,`

**Indexing:** Column-major order based on category definition order. For categories [C0, C1, C2] with sizes [s0, s1, s2]:
```
index = i0 + i1 * s0 + i2 * (s0 * s1)
```
First category (C0) varies fastest, last category varies slowest.

Some models append a `|N` suffix to each value (e.g., `250000.0|6`) — strip this before parsing.

### Cell Constraints

```xml
<cell-constraints>
  <constraint-ranges count="N">
    <item>
      <from size="N">0,0,0</from>       <!-- multi-dimensional start index -->
      <to size="N">2,3,5</to>           <!-- multi-dimensional end index -->
      <constraints>
        <constraint oid="N" factory-id="AnyValueConstraint"/>
      </constraints>
    </item>
  </constraint-ranges>
</cell-constraints>
```

The `size` attribute on `<from>`/`<to>` matches the number of categories. Constraints can be shared via `<constraint ref="N"/>`.

**Constraint factory-ids:**
- `AnyValueConstraint` — accepts any value (default)
- `DateConstraint` — restricts to date values
- `CategoryConstraint` — dropdown restricted to items from a category; has `<parseValues>true</parseValues>`, `<category>` (inline or ref), optional `<dynamic>true</dynamic>`
- `UserListConstraint` — dropdown restricted to explicit values; has `<parseValues>true</parseValues>`, `<valuesList><value>'Add</value><value>'Subtract</value></valuesList>`
- `Boolean` — used as a number-format factory-id for boolean display

---

## 6. Time Dimensions

```xml
<advisor oid="N" factory-id="month">
  <included>true</included>
  <label-generator factory-id="simple">
    <format>Month {0}</format>
  </label-generator>
  <parent oid="N">
    <configs count="4">
      <config oid="N" factory-id="year">
        <label-generator factory-id="simple"><format>Year {0}</format></label-generator>
        <number-of-items>10</number-of-items>
        <range-label-generator factory-id="ranged-year">...</range-label-generator>
        <fiscal-year-label-generator factory-id="fiscal-year">...</fiscal-year-label-generator>
      </config>
      <config factory-id="quarter">...</config>
      <config factory-id="month">...</config>
      <config factory-id="other">...</config>    <!-- weeks, days, half-months -->
    </configs>
    <use-start-date>true</use-start-date>
    <start-month>January</start-month>
    <start-year>2014</start-year>
    <use-fiscal-year>false</use-fiscal-year>
  </parent>
  <number-of-items>12</number-of-items>
  <named-label-generator factory-id="months">
    <use-abbreviations>true</use-abbreviations>
  </named-label-generator>
</advisor>
```

**Advisor `factory-id` values:** `year`, `quarter`, `month`, `other`

**Label generator `factory-id` values:** `simple`, `ranged-year`, `fiscal-year`, `months`, `other`

The `<parent>` element contains the full time hierarchy configuration. A table links to this via `<time-configuration ref="N"/>` pointing to the parent oid.

---

## 7. Model Browser and Views

```xml
<model-browser oid="N">
  <browser-root>
    <label-format>...</label-format>
    <children count="N">
      <child factory-id="BrowserFolder">
        <label-format>...</label-format>
        <name>Folder Name</name>
        <children count="N">
          <child factory-id="BrowserViewNode">
            <view factory-id="spreadsheetView|matrixView|presentationView|...">
              ...
            </view>
          </child>
        </children>
      </child>
    </children>
    <name>__ROOT</name>
  </browser-root>
</model-browser>
```

**Browser node factory-ids:**
- `BrowserFolder` — folder grouping views, has `<name>`, `<children>`
- `BrowserViewNode` — leaf node containing a single `<view>`

**View factory-ids:**
- `spreadsheetView` — traditional grid with formula toolbar
- `matrixView` — outline/report style view
- `presentationView` — canvas with embedded objects
- `com.quantrix.chartgrid.core.ChartGrid` — standalone chart

All views share a common header:
```xml
<view oid="N" factory-id="TYPE">
  <name>View Name</name>
  <uuid>...</uuid>
  <comments count="N"><div>...</div></comments>
  <shared-page-format>
    <left-margin>72.0</left-margin>
    <right-margin>72.0</right-margin>
    <bottom-margin>72.0</bottom-margin>
    <top-margin>72.0</top-margin>
    <header-margin>36.0</header-margin>
    <footer-margin>36.0</footer-margin>
    <page-width>612.0</page-width>
    <page-height>792.0</page-height>
  </shared-page-format>
</view>
```

---

## 8. View Axes and Projections

Spreadsheet and matrix views contain an inner `<view>` with projection state and three axes:

```xml
<view oid="N">
  <state oid="N" factory-id="com.quantrix.core.FullProjectionState">
    <table ref="N"/>                     <!-- which matrix this view shows -->
    <hide-category-tiles>false</hide-category-tiles>
    <hide-collapsed>false</hide-collapsed>
    <show-notes>true</show-notes>
    <scale>1.0</scale>
    <draw-default-lines>true</draw-default-lines>
    <format-source>...</format-source>   <!-- default + per-category formatting -->
    <show-input-cells>false</show-input-cells>
    <cell-format-list/>
    <format-ranges>...</format-ranges>   <!-- cell-level format overrides -->
  </state>
  <column-axis oid="N">
    <categories count="N"><category ref="N"/></categories>
    <default-size>76</default-size>
    <synchronizer oid="N"><horizontal>true</horizontal></synchronizer>
  </column-axis>
  <row-axis oid="N">
    <categories count="N"><category ref="N"/></categories>
    <default-size>18</default-size>
    <mode>Normal</mode>                  <!-- or "Outline" -->
    <sort oid="N"><sort-contexts/><sourceAxis ref="N"/></sort>
  </row-axis>
  <page-axis oid="N">
    <categories count="N"><category ref="N"/></categories>
    <category-source oid="N" factory-id="single|multiple">
      <categories count="N"><category ref="N"/></categories>
      <matrix ref="N"/>
      <item-indices size="N">0</item-indices>
    </category-source>
  </page-axis>
  <filters/>
</view>
```

**Projection state factory-ids:**
- `com.quantrix.core.FullProjectionState` — shows all items
- `com.quantrix.core.PartialProjectionState` — shows a subset (used in charts)

**Page-axis `category-source` factory-ids:**
- `single` — one item selected on page axis
- `multiple` — multiple items (linked to a slicer/sticky table)

**Filters:**

`TopTenFilter`:
```xml
<filter oid="N" factory-id="TopTenFilter">
  <constraint size="N">-2147483648,-2147483648,0</constraint>
  <style>TOP</style>
  <percent>false</percent>
  <number>10</number>
</filter>
```

`ExpressionFilter` — filters rows by a condition expression:
```xml
<filter oid="N" factory-id="ExpressionFilter">
  <constraint size="N">-2147483648,7,-2147483648</constraint>
  <expression>
    <table ref="N"/>
    <aggressive-lists>false</aggressive-lists>
    <condition>:=:&lt;&gt;0</condition>       <!-- e.g., non-zero values -->
    <desired-type>cValue</desired-type>
  </expression>
</filter>
```

`PickFilter` — filters rows by matching specific values:
```xml
<filter oid="N" factory-id="PickFilter">
  <constraint size="N">-2147483648,5,-2147483648</constraint>
  <values count="1">
    <value>
      <value>'Stock Low</value>
      <number-format ref="N"/>
    </value>
  </values>
</filter>
```

---

## 9. Canvas and Presentation Objects

```xml
<view oid="N" factory-id="presentationView">
  <canvas>
    <canvas oid="N">
      <additional-properties>...</additional-properties>
      <name>Canvas Name</name>
      <uuid>...</uuid>
      <objects count="N">
        <object oid="N" factory-id="TYPE">...</object>
      </objects>
      <scale>1.0</scale>
      <shadow>false</shadow>
      <snaps count="7"><flag>true|false</flag>...</snaps>
      <grid-size>25</grid-size>
      <backgroundColor paint-type="color">...</backgroundColor>
    </canvas>
    <slicer ref="N"/>           <!-- optional: links to sticky table category-source -->
    <formats>...</formats>
  </canvas>
</view>
```

### Canvas Object Types

**textBox:**
```xml
<object factory-id="textBox">
  <name>...</name><uuid>...</uuid>
  <location><x>N</x><y>N</y></location>
  <size><w>N</w><h>N</h></size>
  <rotation>0.0</rotation>
  <image-src/>
  <horizontal-align>Left</horizontal-align>
  <vertical-align>Top</vertical-align>
  <background-color paint-type="color"><r/><g/><b/><a/></background-color>
  <border-style>
    <color><r/><g/><b/><a/></color>
    <linePattern>cNoLine|cSolid</linePattern>
    <thickness>1</thickness>
  </border-style>
  <html-content>...HTML-entity-encoded rich text...</html-content>
</object>
```
Supports template expressions: `{@Year}`, `{@Matrix::Item}`.

**shape:**
```xml
<object factory-id="shape">
  <shape-type>Rectangle</shape-type>
  <!-- same location/size/background/border as textBox -->
</object>
```
Shape types: `Rectangle`, `RoundedRectangle`, `Circle`, `Oval`, `IsoscelesTriangle`, `FatArrow`, `PentagonArrow`, `BevelBorder`. `RoundedRectangle` has `cornerArcRatio` in `additional-properties`.

**line:**
```xml
<object factory-id="line">
  <points count="2">
    <point factory-id="coordinatePoint">
      <point><x>N</x><y>N</y></point>
    </point>
  </points>
  <style><color>...</color><thickness>N</thickness><dash-pattern>cSolid</dash-pattern></style>
  <type>BEZIER</type>                              <!-- optional: curved lines -->
  <start-decoration factory-id="none"/>
  <end-decoration factory-id="LinePointFilledArrow">
    <size>Small</size>
  </end-decoration>
</object>
```
Point factory-ids: `coordinatePoint` (absolute x/y position), `shapePoint` (attached to a shape object — contains `<object>` ref). Decoration factory-ids: `none`, `LinePointFilledArrow`, `LinePointFilledCircle`.

**MatrixCanvasObject:**
```xml
<object factory-id="MatrixCanvasObject">
  <location>...</location><size>...</size>
  <background paint-type="color">...</background>
  <projection oid="N">
    <state factory-id="com.quantrix.core.FullProjectionState">
      <table ref="N"/>
      ...same structure as spreadsheet view state...
    </state>
    <column-axis>...</column-axis>
    <row-axis>...</row-axis>
    <page-axis>...</page-axis>
    <filters/>
  </projection>
</object>
```

**group:**
```xml
<object factory-id="group">
  <objects count="N">
    <!-- nested objects of any type -->
  </objects>
  <location>...</location>
</object>
```

**CanvasButton:**
```xml
<object factory-id="com.quantrix.scripting.buttons.CanvasButton">
  <additional-properties>
    <!-- foregroundColor, fontFace, fontSize, fontBold, etc. -->
  </additional-properties>
  <action ref="N"/>    <!-- ref to a ScriptedAction oid -->
</object>
```

**RadioButtonGroup:**
```xml
<object factory-id="RadioButtonGroup">
  <target>
    <matrix ref="N"/>
    <cell-name>Matrix::Item:Category</cell-name>
  </target>
  <value>4.0</value>
  <options><option>1.0</option>...</options>
  <labels><label>Option 1</label>...</labels>
  <label>Question text</label>
  <label-position>TOP</label-position>
</object>
```

**ChartGridCanvasObject** — embedded chart on canvas:
```xml
<object factory-id="ChartGridCanvasObject">
  <scrollable>true</scrollable>
  <chart-grid oid="N">
    <!-- same structure as standalone ChartGrid view -->
  </chart-grid>
</object>
```

**DataMatrixCanvasObject** — data-driven chart/matrix on canvas:
```xml
<object factory-id="DataMatrixCanvasObject">
  <visible>false</visible>
  <data-projection oid="N">
    <chart-grid oid="N">
      <!-- contains chart-definition, projection, axes -->
    </chart-grid>
  </data-projection>
</object>
```
Note: uses `<data-projection>` instead of `<projection>`.

**Slider** — interactive slider targeting a cell:
```xml
<object factory-id="Slider">
  <target>
    <matrix ref="N"/>
    <cell-name>Matrix::Item1:Item2:Item3</cell-name>
  </target>
  <minimum>0.5</minimum>
  <maximum>2.0</maximum>
  <!-- font properties, foreground-color, location, size -->
</object>
```

**StatusLight** — traffic-light indicator for a cell value:
```xml
<object factory-id="StatusLight">
  <target>
    <matrix ref="N"/>
    <cell-name>Matrix::Item1:Item2</cell-name>
  </target>
  <slice>false</slice>
  <!-- font properties, location, size -->
</object>
```

**ChartGridLegend:**
```xml
<object factory-id="ChartGridLegend">
  <visible>false</visible>
  <chart oid="N">
    <!-- contains full chart-grid/chart-definition -->
  </chart>
</object>
```

---

## 10. Charts

```xml
<view factory-id="com.quantrix.chartgrid.core.ChartGrid">
  <chart-grid oid="N">
    <chart-definition oid="N">
      <properties>
        <property>orientation:copy:archive</property>
        <value type="...">Vertical</value>
      </properties>
      <type oid="N" factory-id="Bar|Line|Pie"/>
      <title oid="N">...font, position, alignment...</title>
      <legend oid="N">
        <legendLayout>cLegendVerticalLayout|cLegendHidden</legendLayout>
      </legend>
      <data-label oid="N">...</data-label>
      <axis-styles>
        <axis><axis>yAxis|xAxis</axis><index>0</index></axis>
        <axis-info><properties>...</properties></axis-info>
      </axis-styles>
      <series-list oid="N">
        <default-series-styles>...</default-series-styles>
        <active-series-styles>...</active-series-styles>
      </series-list>
    </chart-definition>
    <projection oid="N">
      <state factory-id="PartialProjectionState|FullProjectionState">...</state>
    </projection>
    <series-axis oid="N"><categories count="1"><category ref="N"/></categories></series-axis>
    <point-axis oid="N"><categories count="1"><category ref="N"/></categories></point-axis>
    <axis-scaling-mode>Row</axis-scaling-mode>
  </chart-grid>
</view>
```

Chart type factory-ids: `Bar`, `Line`, `Pie`, `Stacked Bar`, `BarLineArea` (combo chart).

Chart symbol factory-ids (for data points on line/area charts): `NoSymbol`, `DotSymbol`, `DiamondSymbol`, `SquareSymbol`.

---

## 11. Formatting System

Formatting is layered — later/more-specific definitions override earlier/more-general ones.

### Layer 1: Node Format (view-level defaults)

```xml
<format-source>
  <node-format>
    <vertical-alignment>Middle</vertical-alignment>
    <horizontal-alignment>Left|Automatic</horizontal-alignment>
    <background-color paint-type="color"><r/><g/><b/><a/></background-color>
    <font-face>Lato</font-face>
    <font-size>11.0</font-size>
    <font-bold>false</font-bold>
    <font-italic>false</font-italic>
    <font-strikethrough>false</font-strikethrough>
    <font-underline>false</font-underline>
    <foreground-color><r/><g/><b/><a/></foreground-color>
    <line-thickness>1</line-thickness>
    <line-pattern>cNoLine|cSolid</line-pattern>
    <line-color><r/><g/><b/><a/></line-color>
    <manual-width>false</manual-width>
    <manual-height>false</manual-height>
    <primary-width>N</primary-width>
    <primary-height>N</primary-height>
    <collapsed>false</collapsed>
    <page-break>false</page-break>
  </node-format>
</format-source>
```

### Layer 2: Category Formats

```xml
<category-formats>
  <category ref="N"/>
  <format>
    <attributed>true</attributed>
    <level-formats count="N">
      <level-format>
        <height>14</height>
        <width>76</width>
        <indent>10</indent>
        <line-style oid="N">
          <line-color><r/><g/><b/><a/></line-color>
          <line-thickness>1</line-thickness>
          <dash-pattern>cSolid</dash-pattern>
          <doubled>false</doubled>
          <double-spacing>0</double-spacing>
        </line-style>
      </level-format>
    </level-formats>
    <descriptors/>
    <descriptor-formats/>
  </format>
</category-formats>
```

`level-formats count` matches the nesting depth of the category's group hierarchy.

### Layer 3: Per-Item Node Overrides

```xml
<node-formats count="N">
  <node name="Item Name">
    <font-bold>true</font-bold>
    <collapsed>true</collapsed>
  </node>
  <node name="Group.SubItem" context="ContextName">
    <primary-width>67</primary-width>
  </node>
</node-formats>
```

Nodes are identified by `name` attribute. Dot-qualified paths disambiguate duplicate names across groups. Optional `context` attribute provides additional disambiguation.

### Layer 4: Cell Format Ranges

```xml
<format-ranges>
  <cell-formats count="N">
    <item>
      <from size="N">0,0,0</from>     <!-- multi-dimensional start -->
      <to size="N">3,5,4</to>         <!-- multi-dimensional end -->
      <cell-format oid="N">
        <vertical-alignment>Bottom</vertical-alignment>
        <horizontal-alignment>Automatic</horizontal-alignment>
        <font-face>Lato</font-face>
        <font-size>11.0</font-size>
        <font-bold>false</font-bold>
        <number-format oid="N" factory-id="StandardNumericFormat">
          <format-string>#,##0</format-string>
          <category>Number|General|Custom</category>
          <description>integer comma style</description>
          <locale>["en"-"US"]</locale>
        </number-format>
        <conditional-format oid="N">
          <conditions/>
          <formats/>
        </conditional-format>
      </cell-format>
    </item>
  </cell-formats>
</format-ranges>
```

Ranges stack/cascade — later items override earlier ones. Both `cell-format` and `number-format` can use `ref` for sharing.

**Number format factory-ids:**
- `StandardNumericFormat` / `Standard` — numeric formats (`#,##0`, `0.00%`, etc.)
- `StandardDateTimeFormat` — date/time formats with additional `<dateType>` and `<timeType>` integer fields
- `Boolean` — displays values as true/false

### Color Model

All colors use RGBA with integer 0-255 components:
```xml
<background-color paint-type="color">
  <r>233</r><g>231</g><b>255</b><a>255</a>
</background-color>
```

### Line Styles

```xml
<line-style oid="N">
  <line-color><r/><g/><b/><a/></line-color>
  <line-thickness>1</line-thickness>
  <dash-pattern>cSolid|cNoLine</dash-pattern>
  <doubled>false</doubled>           <!-- double underline (accounting) -->
  <double-spacing>0|1</double-spacing>
</line-style>
```

---

## 12. Scripts

Stored in the model's `<property-store>`:

```xml
<property>com.quantrix.scripting.core.ScriptLibraries:copy:archive</property>
<value type="l">
  <item factory-id="com.quantrix.scripting.core.ScriptLibrary">
    <id>uuid</id>
    <time-stamp>1626113083221</time-stamp>
    <scripts>
      <value factory-id="com.quantrix.scripting.core.ScriptedAction">
        <name>Script Name</name>
        <script>
void perform() {
  openPerspective("Page 3");
  |Navigator|.value = 3;
}
boolean enabled() { return true; }
        </script>
        <id>uuid</id>
      </value>
    </scripts>
    <name>Library1</name>
    <menu-actions>
      <value ref="N" type="x"/>    <!-- refs to ScriptedActions -->
    </menu-actions>
  </item>
</value>
```

### Script Types

**ScriptedAction** — triggered by buttons, menus, or accelerators:
- Must define `void perform() { ... }`
- Optional `boolean enabled() { ... }` to control when the action is available

**ScriptedFunctionSet** — custom functions usable in Quantrix formulas:
```xml
<value factory-id="com.quantrix.scripting.core.ScriptedFunctionSet">
  <name>Circle</name>
  <script>
@FunctionDoc(description="Returns the area of a circle.", argNames=["radius"], argDocs=["the radius"])
double area(double radius) { return Math.PI * (radius * radius); }
  </script>
  <id>uuid</id>
  <preamble>import com.quantrix.scripting.core.internal.FunctionDoc;</preamble>
</value>
```
Functions are called in formulas as `FunctionSetName.functionName()` (e.g., `Circle.area(5)`).

### Script API Reference

Scripts use Groovy/Java syntax. Key APIs:

**Cell references** (pipe-delimited):
- `|Matrix::Item1:Item2|.value` — read/write a cell value
- `|Matrix::Item1:Item2..Item3:Item4|` — range selection
- `|Matrix::Cat|` — category selection
- `|Matrix::Cat|.children` — iterate category items
- `|Matrix::Item^|` — headers only
- `|Matrix::Item*|` — cells only
- `|Matrix::Formula::1|` — first formula
- `|Matrix::Item{Descriptor}|` — item descriptor

**Model manipulation:**
- `matrices.create("Name")` — create a new matrix
- `matrices[0].delete()` — delete a matrix
- `matrix.name = "New Name"` — rename
- `matrix.categories.create("Name", ["A", "B", "C"])` — create category with items
- `category.items[0].name = "New"` — rename items
- `category.delete()` — delete a category
- `presentations[0].delete()` — delete canvas views

**UI interaction:**
- `openPerspective("name")` — switch to a named perspective
- `alert("message")` — show alert dialog
- `ask(message, fieldMap)` — prompt user for typed input (returns map or null if cancelled)
- `ask(message, [("label"):itemsList])` — prompt with dropdown choices

**Variable declarations:** Uses Groovy's `def` keyword:
- `def cell = |Matrix::A1:B1|`
- `def double[] rates = [1.04, 1.01]`

### Keyboard Accelerators

```xml
<property>com.quantrix.scripting.core.AcceleratorMap:copy:archive</property>
<value type="l">
  <!-- list of accelerator bindings, often null entries -->
</value>
```

---

## 13. Data Sources

### Aggregated Data Source Manager

```xml
<property>com.quantrix.aggregation.core.AggregationDataSourceManager:copy:archive</property>
<value factory-id="com.quantrix.aggregation.core.AggregatedDataSourceManager">
  <model ref="1"/>
  <datasources>
    <datasource ref="N"/>
  </datasources>
  <query-names/>
</value>
```

### Column Store Data Source (CSV/text file import)

Found on categories that source data externally:
```xml
<source factory-id="com.quantrix.aggregation.core.ColumnStoreAggregatedDataSource">
  <label>Text File</label>
  <data-config>
    <key>url</key><value type="s">file:/path/to/file.csv</value>
    <key>charset</key><value type="s">UTF-8</value>
    <key>name-to-type-map</key><value type="m">...</value>
  </data-config>
</source>
```

Summarizer factory-ids:
- `com.quantrix.aggregation.core.summarizer.ColumnSum`
- `com.quantrix.aggregation.core.summarizer.ColumnAverage`
- `com.quantrix.aggregation.core.summarizer.ColumnFirst`

### Export Properties

```xml
<export-props factory-id="delimitedTextExportProperties">
  <type>2</type>
  <views count="1"><name>View Name</name></views>
  <include-row-headers>false</include-row-headers>
  <delimiter>	</delimiter>          <!-- tab character -->
  <charset>US-ASCII</charset>
</export-props>
```

Appears at document level, sibling to `<model-browser>`. Configures text export format.

---

## 14. Roles and Permissions

```xml
<modelerRole oid="N">
  <name>Modeler</name>
  <context ref="1"/>
  <grants count="1">
    <grant><context ref="1"/><id></id></grant>  <!-- empty id = full access -->
  </grants>
</modelerRole>
<roles count="3">
  <role ref="N"/>             <!-- Modeler -->
  <role oid="N">
    <name>User</name>
    <grants count="N">
      <grant><context ref="1"/><id>.structure.export</id></grant>
      <grant><context ref="1"/><id>.presentation.tiles</id></grant>
      <grant><context ref="1"/><id>.interaction</id></grant>
      <grant><context ref="1"/><id>.save-model</id></grant>
      <!-- etc. -->
    </grants>
  </role>
  <role oid="N">
    <name>Viewer</name>
    <grants count="2">...</grants>
  </role>
</roles>
```

**Known grant IDs:** `.structure.export`, `.presentation.tiles`, `.presentation.format`, `.presentation.charts`, `.interaction`, `.interaction.switch-perspectives`, `.interaction.presentation-interaction`, `.save-model`, `.structure.datalink.update-datalink`

---

## 15. Property Store

Property stores appear at document, model, category, and child levels. They use a repeating key-value pattern:

```xml
<property-store>
  <property>fully.qualified.ClassName:copy:archive</property>
  <value type="TYPE">...</value>
  <property>another.property:nocopy:archive</property>
  <value type="TYPE">...</value>
</property-store>
```

**Property name format:** `com.package.ClassName:copyFlag:archiveFlag`
- `copy:archive` — serialized and copied
- `nocopy:archive` — serialized but not copied

**Value type codes:**
| Type | Description |
|------|-------------|
| `s` | String |
| `b` | Boolean |
| `int` | Integer |
| `flt` | Float |
| `l` | List (contains `<item>` children) |
| `m` | Map (contains `<key>`/`<value>` pairs) |
| `x` | Complex object (requires `factory-id`) |
| `color` | RGBA color (`<r>`, `<g>`, `<b>`, `<a>`) |
| `rect` | Rectangle (`<x>`, `<y>`, `<w>`, `<h>`) |
| `byte[]` | Base64-encoded binary data |

---

## 16. Headers and Footers

```xml
<headers count="1">
  <item oid="N">
    <name>Header Name</name>
    <left count="1"><div style="..."><span>{$ViewName}</span></div></left>
    <center count="1"><div>...</div></center>
    <right count="1"><div><span>{$PageCategoryItem}</span></div></right>
    <background paint-type="color"><r/><g/><b/><a/></background>
  </item>
</headers>
```

**Template variables:** `{$ViewName}`, `{$Page}`, `{$Pages}`, `{$UserName}`, `{now() | mmmm d, yyyy}`, `{$PageCategoryItem}`

---

## 17. Images

```xml
<value factory-id="com.subx.general.core.ImageList">
  <imageMap>
    <source>qtx://InternalImage:uuid</source>
    <image oid="N" factory-id="com.subx.general.core.BufferedImage">
      <uuid>...</uuid>
      <type>png</type>
      <source>qtx://InternalImage:uuid</source>
      <stored>true</stored>
      <data>...base64-encoded image data...</data>
    </image>
  </imageMap>
</value>
```

Images can be internal (base64 with `qtx://InternalImage:` protocol) or external file references.

---

## 18. Perspectives

Named layout snapshots:

```xml
<property>perspectives:copy:archive</property>
<value type="l">
  <item factory-id="com.quantrix.core.NamedByteData">
    <name>Page 1</name>
    <data>...base64-encoded binary layout...</data>
    <xml>&lt;Root version="1.0"&gt;&lt;View&gt;&lt;editor-root&gt;&lt;Tab&gt;
      &lt;QuantrixView uuid="..."&gt;&lt;/QuantrixView&gt;
    &lt;/Tab&gt;&lt;/editor-root&gt;&lt;/View&gt;&lt;/Root&gt;</xml>
  </item>
</value>
```

Each perspective has a `<name>`, binary `<data>` (serialized Java layout state), and an `<xml>` element describing Tab/Split arrangements referencing views by UUID.

---

## 19. Complete Factory-ID Index

Every `factory-id` value observed across all sample models and templates:

**Child/item types:** `item`, `group`

**View types:** `spreadsheetView`, `matrixView`, `presentationView`, `com.quantrix.chartgrid.core.ChartGrid`

**Browser nodes:** `BrowserFolder`, `BrowserViewNode`

**Projection states:** `com.quantrix.core.FullProjectionState`, `com.quantrix.core.PartialProjectionState`

**Canvas objects:** `textBox`, `shape`, `line`, `group`, `MatrixCanvasObject`, `ChartGridCanvasObject`, `DataMatrixCanvasObject`, `ChartGridLegend`, `Slider`, `StatusLight`, `RadioButtonGroup`, `com.quantrix.scripting.buttons.CanvasButton`

**Chart types:** `Bar`, `Line`, `Pie`, `Stacked Bar`, `BarLineArea`

**Chart symbols:** `NoSymbol`, `DotSymbol`, `DiamondSymbol`, `SquareSymbol`

**Shape types (via `<shape-type>`):** `Rectangle`, `RoundedRectangle`, `Circle`, `Oval`, `IsoscelesTriangle`, `FatArrow`, `PentagonArrow`, `BevelBorder`

**Line points:** `coordinatePoint`, `shapePoint`

**Line decorations:** `none`, `LinePointFilledArrow`, `LinePointFilledCircle`

**Cell constraints:** `AnyValueConstraint`, `DateConstraint`, `CategoryConstraint`, `UserListConstraint`

**Filters:** `TopTenFilter`, `ExpressionFilter`, `PickFilter`

**Number formats:** `StandardNumericFormat`, `Standard`, `StandardDateTimeFormat`, `Boolean`

**Time advisors:** `year`, `quarter`, `month`, `other`

**Label generators:** `simple`, `ranged-year`, `fiscal-year`, `months`, `other`

**Category-source:** `single`, `multiple`

**Category advisor:** `com.quantrix.engine.CellRangeCategoryItemGenerator`

**Scripts:** `com.quantrix.scripting.core.ScriptLibrary`, `com.quantrix.scripting.core.ScriptedAction`, `com.quantrix.scripting.core.ScriptedFunctionSet`, `com.quantrix.scripting.core.CustomButtonConfiguration`

**Data sources:** `com.quantrix.aggregation.core.AggregatedDataSourceManager`, `com.quantrix.aggregation.core.ColumnStoreAggregatedDataSource`, `com.quantrix.aggregation.core.ColumnStoreField`, `com.quantrix.aggregation.core.ColumnStoreRowField`, `com.quantrix.aggregation.core.ColumnStoreRowQuantizer`, `com.quantrix.aggregation.core.FieldExpression`, `com.quantrix.aggregation.core.ValueExpression`, `com.quantrix.aggregation.core.QueryField:com.quantrix.aggregation.core.QuantizedField`, `com.quantrix.aggregation.core.QueryField:com.quantrix.aggregation.core.SummarizedField`, `com.quantrix.aggregation.core.quantizer.ColumnAllValues`, `com.quantrix.aggregation.core.summarizer.ColumnSum`, `com.quantrix.aggregation.core.summarizer.ColumnAverage`, `com.quantrix.aggregation.core.summarizer.ColumnFirst`

**Other:** `com.quantrix.core.NamedByteData`, `com.subx.general.core.ImageList`, `com.subx.general.core.BufferedImage`, `com.quantrix.publisher.core.PublishConfig`, `com.quantrix.dataimport.core.ImportOptions`, `com.subx.general.data.core.FixedWidthField`, `delimitedTextExportProperties`, `validated-node-range`, `first-option`, `standard`
