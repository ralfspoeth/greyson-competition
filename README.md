# greyson-competition

A standalone module that runs the same schema-less JSON tasks with **Greyson**,
**Jackson**, and **Gson**, side by side, so the comparison is executable rather
than rhetorical.

It is deliberately **not** part of the `json` build and is **never** published.
Jackson and Gson appear only here, so the core `json` module keeps its
zero-dependency footprint and clean CVE surface.

## What it shows

`RedactionComparisonTest` implements a recursive, schema-less redaction (mask
the string value of any sensitive key, at any depth) two ways:

- **Greyson** — a total `switch` over the sealed `JsonValue` hierarchy; the
  compiler proves every node shape is handled, and the result is a fresh
  immutable tree.
- **Jackson** — an `instanceof ObjectNode/ArrayNode/…` ladder with a catch-all
  `else`, over a mutable `JsonNode` tree.

The two results are cross-checked for agreement. A second test contrasts the
update models: a Greyson `JsonValue` is immutable by type — editing means taking
a `Builder`, applying `Pointer.set`/`remove` to it, and building a new value, so
the original *cannot* be touched — whereas Jackson requires an explicit full
`deepCopy()` to avoid mutating the shared original.

`ShapeComparisonTest` covers structural checking without a schema — a payload
nobody controls, no schema document to validate against, and two questions to
answer from one description: *which records are usable?* and *what exactly is
wrong with the rest?*

- **Greyson** — a `Shape` states the record's shape once. `parse("records").must(each(RECORD))`
  reports defects with full paths (`records/[1]/amount: expected number, got string`),
  and the *same* value filters via `Selector.all().where(RECORD)`.
- **Jackson / Gson** — neither has a way to *say* the shape, so the walk, the
  type test, and above all the path assembly are written out by hand, once per
  field, and again for every new record type.

All three are asserted to flag the same defective paths and keep the same usable
records, so the contrast is in the code, not the result.

`PortfolioMappingComparisonTest` goes wider: it maps a noisy portfolio document
into a record graph (`Portfolio`/`Position`/`Instrument`) three ways — with
**Greyson**, **Jackson**, and **Gson** — and asserts all three produce equal
graphs. Its mapping rules (a coded `"%"` enum, field-derived `name`/`localCcy`
defaults, a context-dependent `valueLocal`, and array-or-single `positions`)
all fall outside reflective binding, so the Jackson and Gson versions both drop
to hand-rolled tree walks while Greyson stays explicit pointer/stream code.

## Running it

```sh
cd greyson-competition
mvn test
```

Bump `<jackson.version>`, `gson.version` and `greyson.version`
in `pom.xml` to whatever Jackson, Gson and Greyson release you want to
compare against.
