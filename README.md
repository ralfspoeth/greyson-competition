# greyson-competition

A standalone module that runs the same schema-less JSON tasks with **Greyson**,
**Jackson**, and **Gson**, side by side, so the comparison is executable rather
than rhetorical.

It is deliberately **not** part of the `json` build and is **never** published.
Jackson and Gson appear only here, so the core `json` module keeps its
zero-dependency footprint and clean CVE surface.

## What it shows

### Recursive redaction

`RedactionComparisonTest` and `GreysonShinesTest` implement a schema-less
redaction — mask the string value of any sensitive key, at any depth — and
cross-check the results:

- **Greyson** — a total `switch` over the sealed `JsonValue` hierarchy; the
  compiler proves every node shape is handled, and the result is a fresh
  immutable tree.
- **Jackson / Gson** — an `instanceof ObjectNode/ArrayNode/…` ladder with a
  catch-all `else`, over a mutable tree.

They also contrast the update models. Both libraries copy before editing; the
difference is who guarantees it. Greyson's copy is the `Builder`, and the source
`JsonValue` has no mutating API at all, so the original *cannot* be touched.
Jackson's and Gson's nodes are mutable, so `deepCopy()` is a convention you have
to remember — and both tests end with the footgun that guards against: forget
the copy and you mutate the document everyone else is holding.

### Mapping into records

`PortfolioMappingComparisonTest` maps a noisy portfolio document into a record
graph (`Portfolio`/`Position`/`Instrument`) three ways and asserts all three
produce equal graphs. Its mapping rules — a coded `"%"` enum, field-derived
`name`/`localCcy` defaults, a context-dependent `valueLocal`, array-or-single
`positions` — all fall outside reflective binding, so Jackson and Gson drop to
hand-rolled tree walks while Greyson stays explicit pointer/stream code.

### Checking structure without a schema

`ShapeComparisonTest` answers two questions from one `Shape`: *which records are
usable* and *what exactly is wrong with the rest*. Greyson reports defects with
full paths (`records/[1]/amount: expected number, got string`) and filters with
the same value via `Selector.where`. Jackson and Gson have no way to *say* the
shape, so the walk, the type test and above all the path assembly are written
out by hand.

`JsonRpcComparisonTest` takes that to a real specification. The
[JSON-RPC 2.0](https://www.jsonrpc.org/specification) request object is a good
test of a shape language because its rules are not merely "these members, those
types": `jsonrpc` must equal the *literal* `"2.0"`; `id` is optional *but
constrained*, where absent means the message is a notification and no response
is owed; `params` likewise. Absence carries meaning, so conflating "absent" with
"present but malformed" is not leniency — it turns a request into a notification
and leaves the peer waiting for a reply that never comes.

Greyson states the whole thing as one value, and `explain()` prints it back:

```
"jsonrpc": "2.0" and "method": string and "id"?: (string or number or null) and "params"?: (array or object)
```

Jackson and Gson need a hand-written null-and-type check per rule, and both
coerce on the convenient accessors — Jackson's `asText()` turns the number `42`
into `"42"`, Gson's `getAsString()` does the same — so the *shorter* code is the
wrong code. All twelve messages are classified identically by all three; only
Greyson can say why.

### Duplicate member names

`DuplicateKeyComparisonTest` settles what all three do with `{"a": 1, "a": 2}`:
collapse to the last mapping, silently. RFC 8259 leaves it to the
implementation, and this is the place that claim gets checked rather than
assumed. Jackson can additionally be configured to reject duplicates outright;
Greyson cannot.

## Running it

```sh
cd greyson-competition
mvn test
```

Bump `<jackson.version>`, `gson.version` and `greyson.version`
in `pom.xml` to whatever Jackson, Gson and Greyson release you want to
compare against.
