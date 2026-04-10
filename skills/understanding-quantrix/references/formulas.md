# Quantrix Formula Reference

Quantrix formulas are stored per-matrix in `<formula-list>`. Each formula applies to the intersection it describes — Quantrix resolves which cells are affected based on the item and category references in the expression.

## Assignment and scope

```
Revenue = Units * Price
```

A formula applies to every cell matching the left-hand side. With no LHS item qualifier, it applies to all cells in the matrix not overridden by a more specific formula.

```
Budget:Revenue = 'Sales Projection'::Channel.Total:Product.Total
```

Multiple item qualifiers separated by `:` specify the exact intersection (category-free, order-independent).

```
In Units, Quarter[THIS] = Quarter[PREV] * (1 + Growth::Rate)
```

`In Item, expr` scopes the formula: it only applies when the current context includes that item.

## Positional references

| Syntax | Meaning |
|--------|---------|
| `Cat[THIS]` | Current position in Cat |
| `Cat[PREV]` | Previous item (hard — error at boundary) |
| `Cat[NEXT]` | Next item |
| `Cat[~PREV]` | Soft previous — empty/zero at boundary, not an error |
| `Cat[FIRST]` | First item |
| `Cat[LAST]` | Last item |
| `Cat[FIRST+2]` | Third item |
| `Cat[LAST-1]` | Second-to-last item |
| `Cat[PREV-2]` | Three positions back |
| `Cat[THIS-2]` | Two before current |
| `Cat[2]` | Item at absolute position 2 (1-based) |
| `#Year` | Numeric index of current Year item (1-based) |
| `(#Year - 1)` | Zero-based index |
| `#Cat1:Cat2` | Compound index reference |

Soft `~PREV` is idiomatic for cumulative running totals where the first period has no prior value:

```
Cumulative:Year[THIS] = Cumulative:Year[~PREV] + Net Impact
```

## Range notation

```
sum(Revenue .. COGS)               // items Revenue through COGS inclusive
sum(Year1:'Discounted CF' .. Year4:'Discounted CF')
```

Ranges are always inclusive. Works in any function that accepts a list argument.

## Cross-matrix references

```
Assumptions::Tax Rate              // Tax Rate item in the Assumptions matrix
'P&L Assumptions'::Growth Rate     // quoted name with special chars
Capex::Sum of Assets:Depreciation  // item + intersection qualifier
```

**Inter-model references** (link to another open model):
```
!source.model!Matrix1::element:item
```

## Dimension mapping with `using ... as`

Required when pulling data across matrices whose categories have related but differently-named dimensions:

```
Forecast Sales = sum(select(Sales Forecast::Forecast Sales,
                            Sales Forecast::Calendar Month, @Month))
                 using Sales Forecast::Calendar Year as Year
```

Also works on the formula level:

```
Assessed Value = sum(Property Data Master::Value)
USING Property Data Master::Classification AS PropClass,
      Property Data Master::Value Bucket AS Bucket
```

## `skip` clause

Excludes specific items from formula application:

```
Year[THIS] = Year[PREV] * 1.05 skip YTD, Total
Net = Revenue - Total Expenses skip Variance
Revenue = sum(summary(Net Revenue)) skip Total, All Regions
```

Skip ranges also work: `skip Year[FIRST] .. Year[LAST-1]`.

## Hierarchy and grouping

```
Total = sum(summary(Channel))          // sum leaf children of the Channel group
Total Assets = sum(summary(Assets))    // recursive: all leaves under Assets
Sales.Cost of Sales                    // nested group path: item within group
```

`summary(Group)` returns the children of a named group item. For multi-level hierarchies this sums only the direct leaves.

## `select` and filter functions

```
select(values, keys, match)               // items from 'values' where 'keys' = match
select(@'List'::Index, isblank('List'::), #true#)   // boolean predicate form
selectbetween(vals, keys, lo, hi)
selectgreaterthan(vals, keys, threshold)
selectlessthan(vals, keys, threshold)
selects(vals, keys, "<3")                 // string comparison operator
selectlike(vals, keys, pattern)           // pattern match
```

The `@` prefix in `@Matrix::Cat` returns the item *name* (string) rather than its value — used to build key lists for lookups.

## Lookup functions

```
lookup(value, key_list, result_list)           // returns result where key matches value
match(value, list)                             // 1-based position of value in list
choose(n, v1, v2, v3, ...)                    // nth argument
first(list) / first(list, #TRUE#)             // first element; #TRUE# skips empties
last(list) / last(list, #TRUE#)               // last element; #TRUE# skips empties
valueat(list, position)                        // element at 1-based position
sublist(list, from)                            // from position to end
sublist(list, from, to)                        // inclusive sub-range
distinct(list)                                 // deduplicated list
sort(list)                                     // sorted ascending
sort(list, #FALSE#)                           // sorted descending
index(list, lookupValue)                       // 1-based index of value in list
filter(list, condition)                        // items matching a boolean condition
filterbytype(list, type)                       // items of a specific type
concatlists(list1, list2, ...)               // concatenate multiple lists
selectlist(values, keys, lookup_list)          // multi-value select (list of match values)
rows(range)                                    // row count of a range
transpose(array)                               // flip rows/columns
indirect(range)                                // dereference a range by string name
countall(list, lookupValue)                    // count occurrences of value in list
```

## Structural / metadata functions

```
@item(B)                  // name of current item in category B
@id(B)                    // internal OID of current item
@level(B, 1)              // item at level 1 within B
@level(B, 1, 3)           // levels 1 through 3 (dot-separated path)
@level(B, *)              // fully qualified name
@itemcount(Matrix::B)     // number of items in category B
@descriptor(B, Units)     // item descriptor named "Units" for category B
@silent(Cat)              // suppresses category from formula display
value(@Range)             // coerce item name string to its numeric/lookup value
```

## List construction and inspection

```
list()                    // empty list
list(3)                   // single-element list
list("word", 7, "", err()) // multi-element
size(list)                // count
isemptylist(list)         // boolean
join(list, ", ")          // concatenate to string
```

## Conditional / logical

```
if(condition, true_val, false_val)
if(#Val > 2, #Val*2, #Val/2)           // self-reference via #
and(a, b) / or(a, b) / not(a)
a #AND# b / a #OR# b / #NOT# a         // infix operators
#TRUE# / #FALSE#                        // boolean literals
between(value, low, high)              // inclusive range test
between(value, low, high, #FALSE#)     // exclusive
case(test, case1, val1, case2, val2, ..., default)   // multi-branch
switch(expr, val1, result1, val2, result2, ..., default)
```

## Error and blank handling

```
clearerror(expr)                        // return empty on error
clearerror(expr, "default")            // return "default" on error
clearzero(expr, "cleared")             // replace zero with string
clearblank(list, replacement)          // replace blank entries in a list
isblank(x) / isempty(x)               // blank/empty check
iserr(x) / iserror(x) / isna(x)
isnumber(x) / istext(x) / isnontext(x) / iszero(x) / isdate(x)
err() / err("#VALUE") / na()           // create error/NA values
```

## Math functions

`abs`, `ceiling(x,sig)`, `floor(x,sig)`, `round(x,n)`, `roundup`, `rounddown`, `trunc`, `int`, `mod(x,d)`, `power(x,n)` or `x^n`, `sqrt`, `exp`, `ln`, `log(x)`, `log(x,base)`, `log10`, `sign`, `even`, `odd`, `fact`, `pi()`, `rand()`, `randmt()`, `randbetween(lo,hi)`, `gcd(a,b)`, `lcm(a,b)`, `product(list)`, `sumsq(list)`, `sumx2my2`, `sumx2py2`, `sumxmy2`, `sumproduct(a,b)`, `subtotal(fn_num, range)`

## Statistical functions

`sum`, `average`, `avedev`, `count`, `counta`, `countblank`, `min`, `max`, `maxa`, `mina`, `stdev`, `stdevp`, `stdeva`, `stdevpa`, `var`, `varp`, `vara`, `varpa`, `median`, `mode`, `rank`, `large(list,k)`, `small(list,k)`, `percentile`, `percentrank`, `quartile`, `trimmean`, `devsq`, `harmean`, `geomean`, `covar`, `correl`, `forecast`, `intercept`, `slope`, `rsq`, `pearson`, `steyx`, `trend(dep,[ind],[new],[const])`, `expa`, `expb`, `expforecast`, `prob`, `combin`, `permut`

Date-aware: `mindate`, `maxdate`, `mediandate`

Conditional: `countif(list, ">3")`, `sumif(list, ">5")`, `sumif(keys, ">5", values)`

AlphaNumeric variants (include text): `averagea`, `counta`, `stdeva`, `stdevpa`, `vara`, `varpa`

## Financial functions

```
npv(rate, cf1, cf2, ...)       // or npv(rate, list)
irr(cash_flows)                // cash flows list including initial outflow
irr(cash_flows, guess)
pmt(rate, nper, pv)            // periodic payment
pmt(rate, nper, pv, fv)
ipmt(rate, period, nper, pv)   // interest portion
ppmt(rate, period, nper, pv)   // principal portion
fv(rate, nper, pmt, pv)
pv(rate, nper, pmt)
nper(rate, pmt, pv)
rate(nper, pmt, pv)
db(cost, salvage, life, period, month)   // declining balance
ddb(cost, salvage, life, period, factor) // double declining
sln(cost, salvage, life)                 // straight line
syd(cost, salvage, life, period)         // sum of years digits
vdb(cost, salvage, life, start, end)     // variable declining
mirr(flows, finance_rate, reinvest_rate)
xnpv(rate, values, dates)             // NPV with irregular dates
xirr(values, dates, [guess])          // IRR with irregular dates
```

## Date / time functions

```
today() / now()
date(year, month, day)
time(hour, minute, second)
year(d) / month(d) / month(d, "Jan") / month(d, "January")
day(d) / hour(d) / minute(d) / second(d)
dayofyear(d) / weekday(d, mode)
datevalue(text) / timevalue(text)
days360(start, end) / days360(start, end, #TRUE#)  // European method
datedif(date1, date2, interval)        // difference in "Y", "M", "D", etc.
edate(start_date, months)             // date N months from start
eomonth(start_date, months)           // end of month N months from start
text(date, "mmm dd, yyyy")    // format date as string
todate(x)                     // coerce to date
```

Timeline category extras:
```
timelinedate()                     // automatic date value for timeline items
Month[~PREV]:Item + 1              // soft recursion on timeline
Smart Ranges:May:'2021' .. Smart Ranges:February:'2023' = 7  // smart range assignment
```

## Text functions

`concatenate(a, b, ...)` or `a & b` infix, `join(list, delim)`, `left(s,n)`, `right(s,n)`, `mid(s,start,len)`, `len(s)`, `find(sub,s)`, `search(sub,s)`, `replace(s,start,len,new)`, `substitute(s,old,new)`, `substitute(s,old,new,"*")` (replace all), `trim(s)`, `upper(s)`, `lower(s)`, `proper(s)`, `rept(s,n)`, `exact(a,b)`, `clean(s)`, `char(n)`, `code(s)`, `value(s)`, `text(n,format)`, `fixed(n,decimals)`, `dollar(n,decimals)`, `roman(n)`, `s(range)` (stringify), `N(x)` (numeric coerce), `hyperlink(url, [display_text])`

## Probability distribution functions

`normdist(x,μ,σ,cumulative)`, `normsdist(z)`, `norminv`, `normsinv`, `lognormdist`, `loginv`, `betadist`, `betainv`, `binomdist`, `chidist`, `chiinv`, `critbinom`, `expondist`, `fdist`, `finv`, `gammadist`, `gammainv`, `gammaln`, `hypgeomdist`, `negbinomdist`, `poisson`, `tdist`, `tinv`, `weibull`, `confidence`, `fisher`, `fisherinv`, `standardize`, `kurt`, `skew`, `chitest`, `ftest`, `ttest`, `ztest`

## Trigonometric functions

`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2(y,x)`, `sinh`, `cosh`, `tanh`, `asinh`, `acosh`, `atanh`, `degrees`, `radians`, `pi()`

## Comments

```
// single-line comment in a formula
/* multi-line comment */
```

Comments are used both as documentation and as a technique to temporarily disable a formula (`//formula text`) — a pattern seen in circular-reference workarounds.

## Percentage literals

`101%` is parsed as `1.01`. Used directly in formulas: `Quarter[THIS] = Quarter[PREV] * 101%`.

## Environment variables

Available in formulas, headers, and canvas text:

| Variable | Returns |
|----------|---------|
| `$UserName` | Current user |
| `$FileName` | Model file name |
| `$FilePath` | Full file path |
| `$FileSavedDate` | Last save date |
| `$ViewName` | Current matrix/chart/canvas name |
| `$MatrixName` | Associated matrix name |
| `$ViewComments` | Model Browser comments |
| `$PageItem` | Selected filter item (print context) |
| `$RoleName` | Logged-in role (Qloud only) |
| `$Page` / `$Pages` | Current/total page numbers (print context) |

## Common patterns

**Running total with soft recursion:**
```
Cumulative:Year[THIS] = Cumulative:Year[~PREV] + Net Impact
```

**Year-over-year growth with skip:**
```
Year[THIS] = Year[PREV] * (1 + Assumptions::Growth Rate)
skip Revenue, Gross Margin, Total
```

**Cross-matrix select with dimension mapping:**
```
Forecast Sales = sum(select(Sales Forecast::Forecast Sales,
                            Sales Forecast::Calendar Month, @Month))
                 using Sales Forecast::Calendar Year as Year
```

**Conditionally blank a range:**
```
Year1:'Accumulated depreciation' .. Year10:'Amortization term' = ""
```

**Circular reference workaround (via script):**
Comment out the offending formula, call `ensureCalculated()`, then uncomment — see scripts reference.
