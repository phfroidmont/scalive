# Form Architecture

Status: proposed architecture

This document defines the target architecture for Scalive forms. It is intentionally a redesign of
the current API rather than a compatibility-preserving extension. Scalive is alpha, so the final
implementation should prefer one coherent API over public compatibility wrappers.

The design covers:

- scalar and repeated scalar fields;
- refinement into domain values;
- arbitrary product construction;
- metadata-free editable values;
- validation and interaction state;
- typed LiveView form events;
- repeated records with stable row identity;
- programmatic updates;
- Phoenix nested-parameter compatibility;
- an optional dirty/reset/save workflow.

The exact class and method names remain subject to implementation feedback. The boundaries,
invariants, and semantics in this document are the intended contract.

## Decisions

The following decisions are settled for the initial design:

| Concern | Decision |
| --- | --- |
| Editable input and domain value | Model them as separate field type parameters. |
| Product construction | Use explicit heterogeneous tuples checked with `Mirror.ProductOf`. |
| Automatic field derivation | Defer it; fields remain explicit stable values. |
| Repeated-row identity | Use stable opaque keys in the core and a separate Phoenix adapter. |
| Row-key allocation | Keep core operations pure by requiring the caller to supply a key. |
| Dirty equality | Compare canonical metadata-free form values, not decoded domain values. |
| Save completion | Replace current values only when they have not changed since submission. |
| Save concurrency | Permit one in-flight save; reject another until it completes or is cancelled. |
| Persistence workflow | Keep it outside the fundamental form model as an optional utility. |

## Current Limitations

The current API has several useful foundations but exposes concepts at the wrong boundaries for a
complete form system.

### Decode-Only Fields

`FormCodec[A]` can `map`, `emap`, and `zip`, but `FormField[A]` exposes only successful `map`,
predicate `validate`, and `required`. Refining a string into a domain value therefore requires a
complete low-level field decoder.

Fields also have no typed editable-input representation. Once a field maps `String` to a domain
type, the framework cannot accept a typed programmatic input without either guessing an inverse or
falling back to raw `Vector[String]` values.

### Fixed Product Arity

Convenient `FormRoot.form` overloads stop at five fields. Larger forms must compose nested `zip`
calls and destructure nested tuples manually. The limit is accidental rather than semantic.

### No Semantic Draft Values

`FormData` is a lossless browser payload. It can contain successful controls, duplicate names,
`_unused_*` markers, CSRF fields, structural nested-form controls, and unknown names. It is the right
protocol representation but the wrong value for draft equality, reset baselines, or save snapshots.

`FormState` combines raw data, validation, used paths, and submission visibility. Applications that
need only user-controlled values must project every field manually.

### Static Paths

A `FormField` owns one complete static path. There is no path template that can be bound to a
repeated row. Repeated scalar values under one exact name work, but repeated records do not.

### Positional Dynamic Rows

The current dynamic-input end-to-end fixture constructs indexed names manually, decodes sort and
drop arrays, scans textual prefixes, and reconciles records by index. This does not provide stable
row identity, row-specific field handles, or row validation state.

### Lossy Browser Paths

`FormPath.parse` currently drops empty array segments such as `[]` and permissively normalizes
malformed bracket syntax. That behavior cannot safely support structural row decoding or exact
Phoenix parameter translation.

### Inconsistent Public State Is Constructible

Public constructors allow a `FormState` or `FormEvent` whose raw payload and decoded value disagree.
`FormDefinition.from(state)` trusts that relationship without decoding again.

### No Save Workflow

The immutable form itself has no baseline, dirty state, submitted snapshot, reset operation, save
failure, or edit revision. These are useful together, but save concurrency and merge policies vary
by application and should not become mandatory form behavior.

## Goals

The target architecture must:

- preserve invalid user input exactly enough to render and compare it;
- keep protocol metadata out of editable values;
- retain root ownership checks across fields, rows, forms, values, and updates;
- make raw payload and decoded result internally consistent;
- make field-level domain refinement ergonomic;
- construct arbitrary products without generated arity overloads;
- accumulate independent field and row errors deterministically;
- give repeated rows stable identity across validation, reordering, recovery, and rendering;
- keep core row operations pure and keyed rather than positional;
- preserve Phoenix compatibility at a boundary rather than copying Ecto into the core;
- support exact draft equality even while decoding fails;
- provide a safe optional save workflow without prescribing persistence policy;
- retain explicit low-level escape hatches for custom controls and protocol tests.

## Non-Goals

The core form model will not:

- infer database insert, update, delete, or `on_replace` behavior;
- authorize a persisted entity from a submitted row key or hidden ID;
- automatically merge server-canonical data into newer edits;
- use decoded-domain equality as the default dirty definition;
- provide durable, offline, or collaborative draft storage;
- make multipart upload ownership part of scalar form decoding;
- automatically derive all fields from case-class labels in the first version;
- hide asynchronous work or application messages inside form state;
- preserve the current public API when a cleaner replacement is available.

## Architectural Layers

Forms are divided into five layers:

| Layer | Responsibility |
| --- | --- |
| `FormData` | Lossless browser and HTTP payload. |
| `FormValues` | Canonical, schema-recognized, metadata-free editable values. |
| Form schema | Input decoding, validation, products, and repeated-group structure. |
| `Form` | Current values, validation result, and interaction state. |
| `FormWorkflow` | Optional baseline, dirty, reset, submission, and save outcome state. |

Each layer may depend only on layers above it in this table. In particular, `FormValues` must not
depend on validation visibility or persistence state, and the form schema must not depend on a save
workflow.

## Transport Data

`FormData` remains the lossless transport representation:

```scala
final case class FormData private (
  raw: Vector[(String, String)]
)
```

It preserves:

- exact browser names;
- empty strings;
- duplicate values;
- global encounter order;
- unknown controls;
- malformed names for diagnostics;
- protocol-specific hidden controls.

It remains the input to URL-encoded body decoding, Phoenix event decoding, low-level test adapters,
and raw form bindings. It is also available from a typed `FormEvent` for debugging and auditing.

`FormData` is not used directly for dirty equality or as a persistence baseline.

The current single-name `FormValues` wrapper should be renamed, for example to
`FormFieldValues`, so `FormValues` can name the complete semantic draft representation.

## Browser Paths

Browser form names need strict, round-trip-safe parsing. Array segments must be represented
explicitly rather than discarded.

Conceptually:

```scala
enum FormPathSegment:
  case Name(value: String)
  case Array

final case class FormPath private (
  segments: Vector[FormPathSegment]
)
```

The implementation may use a different representation, but it must satisfy:

```scala
FormPath.parse("users_sort[]").map(_.name)
// Right("users_sort[]")
```

Parsing malformed names returns a representation error. It must never throw while decoding an
untrusted payload, and it must not silently change a malformed name into a different valid path.
The original pair remains available in `FormData` even when parsing fails.

The permissive legacy parser may remain as an explicitly unsafe or compatibility-named function if
a concrete caller needs it. It must not drive typed schema projection.

## Logical Addresses

Validation, interaction, and row identity must not depend on a mutable browser index. Introduce an
owner-scoped logical address:

```scala
final class FormAddress[Owner] private[scalive] (...)
```

A logical address identifies:

- one declared static field;
- one repeated group;
- one stable row within a group;
- one declared field within that row;
- eventually, deeper nested scopes without changing the model.

Errors and used-state tracking refer to logical addresses. Rendering and protocol adapters resolve
those addresses to browser names and DOM IDs.

DOM IDs must use an injective encoding of logical addresses. The existing underscore-joined
`FormPath.id` is not injective and must not be the identity mechanism for repeated rows.

## Semantic Form Values

`FormValues` is the first-class representation of user-controlled values. Its `Schema` parameter is
the exact form-definition identity:

```scala
final class FormValues[Owner, Schema] private[scalive] (...)
```

Only a form definition or one of its typed operations constructs it. Applications may retain and
compare it, but cannot create an internally malformed instance.

### Contents

`FormValues` preserves:

- absence versus presence of a recognized control;
- empty values;
- duplicate scalar submissions;
- repeated scalar order;
- repeated-row order;
- stable row keys;
- every recognized row field's raw values;
- invalid text that fails decoding or refinement.

It excludes:

- `_unused_*` markers;
- CSRF controls;
- submitter metadata;
- recovery metadata;
- Phoenix sort and drop bookkeeping after translation;
- row-presence controls after translation;
- validation errors;
- validation visibility;
- unknown controls not declared by the schema.

Identity-bearing structural controls are excluded as raw entries only after their meaning is
translated. Row-presence controls, Phoenix `_persistent_id`, and sort/drop controls can change row
keys or order in `FormValues`; such a change is semantic and may make a workflow dirty. Inert
metadata such as target, recovery provenance, and `_unused_*` visibility markers cannot.

Unknown and malformed payload entries remain available through `FormData`. Recognized malformed
input must produce a form error rather than disappear silently.

A well-formed name that is not declared by the schema is ignored by typed projection and validation.
This permits named buttons and unrelated custom controls without making them domain values. A
syntactically malformed name produces a root structural error with a stable machine code and is
excluded from `FormValues`; it is never guessed into a declared address. Within a recognized
repeated group, a leaf under a key without a valid row-presence control produces a group structural
error.

### Canonical Structure

The internal representation should be a logical tree or equivalent canonical structure rather than
the global pair order from `FormData`. It must distinguish:

- a logical address and its ordered raw values;
- a repeated group and its ordered row keys;
- each keyed row and its field values.

Static field declaration order does not affect equality. Repeated values and repeated-row order do.

### Equality

`FormValues` equality is exact semantic draft equality. It compares:

- every recognized field's raw value vector;
- repeated scalar order;
- row order;
- row keys;
- every row field's raw value vector.

It does not compare:

- decoded domain values;
- validation errors;
- used fields;
- error visibility;
- event kind or target;
- protocol metadata.

Consequences:

- normalization does not make visibly different input clean;
- invalid drafts can still be compared;
- an inert metadata-only browser event does not make a form dirty;
- removing a row and adding an identical-looking replacement remains dirty because its key differs.

If an application needs semantic domain equality or selected-field equality, it supplies that policy
outside the core form model.

### Definition Identity

Values retain both root ownership and exact form-definition identity. Forms, events, snapshots, and
workflows carry the same hidden `Schema` parameter, so values produced by another definition do not
type-check even when both definitions share one root and domain type. Path-dependent aliases on
`FormDefinition` hide this additional identity from ordinary application signatures.

## Field Inputs

Editable input and decoded value are separate concepts:

```scala
trait FieldInput[Input]:
  def decode(raw: Vector[String]): Either[FieldIssues, Input]
  def encode(input: Input): Vector[String]
```

`FieldInput` handles browser cardinality and typed programmatic input. It does not perform domain
validation.

Examples:

| Constructor | Input type | Structural behavior |
| --- | --- | --- |
| `text` | `String` | Missing becomes the configured empty/default input; duplicates fail. |
| `optionalText` | `Option[String]` | Missing or configured empty input becomes `None`; duplicates fail. |
| `texts` | `Vector[String]` | Every exact-name value is retained in order. |
| custom | Application-defined | Explicitly defines raw cardinality and encoding. |

The raw vector remains in `FormValues` if structural decoding fails. Losslessness belongs to
`FormValues`, not to the successful `Input` projection.

Typed initialization and programmatic updates use `Input`. Encoding therefore remains available
after the field refines its successful value into an unrelated domain type.

## Form Fields

A field carries root ownership, editable input, and decoded value types:

```scala
final class FormField[Owner, Input, Value] private[scalive] (...)
```

`FormRoot` provides a local alias so applications rarely write the full type:

```scala
val ProfileRoot = FormRoot("profile")

val Name = ProfileRoot.text("name")
// ProfileRoot.Field[String, String]
```

### Transformations

Field transformations change only the successful `Value` type:

```scala
def map[B](f: Value => B): FormField[Owner, Input, B]

def emap[B](
  f: Value => Either[FieldIssues, B]
): FormField[Owner, Input, B]

def validate(
  issue: FieldIssue
)(
  predicate: Value => Boolean
): FormField[Owner, Input, Value]
```

`FieldIssue` is pathless:

```scala
final case class FieldIssue(
  message: String,
  code: Option[String] = None
)
```

The field attaches its logical address automatically. Applications refining a string do not need to
construct `FormData`, `FormPath`, or `FormErrors`.

`emap` is dependent: it runs only after structural input decoding and preceding transformations
succeed. Independent fields still accumulate errors through product composition.

If multiple independent rules for one successful value need accumulation, provide an explicit
`validateAll` operation rather than changing `emap` into applicative behavior.

### Refinement Example

```scala
val Email =
  ProfileRoot
    .text("email")
    .map(_.trim)
    .required(FieldIssue("Email is required", Some("required")))
    .emap(value =>
      EmailAddress
        .parse(value)
        .left
        .map(message => FieldIssues.one(FieldIssue(message, Some("invalid_email"))))
    )
```

The resulting field has an editable input of `String` and a successful value of `EmailAddress`.

### Typed And Raw Assignments

Typed input is the normal initialization and update path:

```scala
Email.initial("ada@example.com")
form.updated(Email, "grace@example.com")
```

An explicit schema-local raw escape hatch remains available:

```scala
Email.raw(Vector("first", "duplicate"))
form.updatedRaw(Email, Vector("first", "duplicate"))
```

Raw updates still rerun the complete form definition and preserve the malformed values and resulting
errors.

## Form Parts

Scalar fields and repeated groups must compose through one abstraction:

```scala
trait FormPart[Owner, Value]
```

A static `FormField[Owner, Input, Value]` is a `FormPart[Owner, Value]`. A completed repeated-row
schema is a `FormPart[Owner, Vector[RowValue]]`.

The abstraction is for schema composition. It does not erase concrete field or group handles used
for rendering and updates.

## Product Construction

Products use explicit heterogeneous tuples and `Mirror.ProductOf`:

```scala
final case class Profile(
  name: NonEmptyString,
  email: EmailAddress,
  tags: Vector[String]
)

val Definition =
  ProfileRoot.product[Profile](
    (Name, Email, Tags)
  )
```

Inline tuple recursion verifies that the tuple of each `FormPart`'s `Value` types exactly equals the
product's `MirroredElemTypes`. On success, the implementation calls `Mirror.ProductOf.fromProduct`
or `fromTuple`.

This provides:

- arbitrary arity;
- products larger than 22 fields;
- compile-time output type and order checks;
- explicit field handles;
- deterministic validation order;
- no generated overloads;
- no nested `zip` destructuring.

Tuple order is constructor order. Mirror labels do not automatically associate a field path with a
case-class parameter. Custom form names may intentionally differ from Scala member names.

Schema construction must reject duplicate logical addresses and other incompatible declarations.

The first implementation should not use named tuples or a quoted macro for its public contract.
Named tuples are experimental in Scala 3.8, and macros are not needed for explicit product
construction. A later optional derivation layer may create conventional fields from
`MirroredElemLabels` and typeclass-provided field policies.

### Product-Level Refinement

A completed product definition retains successful `map` and `emap` operations for cross-field
validation or transformation. Product-level errors may target:

- the form root;
- a group address;
- a specific declared field through its handle.

Independent leaf validation belongs on leaf fields so errors continue to accumulate.

## Form Definitions

A `FormDefinition[Owner, Domain]` owns:

- the root;
- the complete schema;
- product construction;
- logical address allocation;
- payload projection;
- validation;
- initial-value construction;
- event bindings;
- rendering lookup;
- form rebuilding after typed updates.

It exposes path-dependent aliases:

```scala
Definition.Form
Definition.Event
Definition.Values
```

These aliases should hide owner and schema identity parameters from models and messages.

Only the definition may create a form whose values and validation result are paired. There is no
public equivalent of the current unchecked `FormDefinition.from(state)`.

## Form Instances

The immutable form is the fundamental runtime value:

```scala
final class Form[Owner, Schema, Domain] private[scalive] (
  val values: FormValues[Owner, Schema],
  val result: Either[FormErrors[Owner], Domain],
  val interaction: FormInteraction[Owner]
)
```

The concrete implementation also retains its exact definition identity privately.

Convenience accessors include:

```scala
form.isValid
form.valueOption
form.errors
form.field(Name)
form.rows(QualificationRows)
```

### Initialization

Definitions create pristine forms from typed assignments:

```scala
Definition.initial(
  Name.initial("Ada"),
  Email.initial("ada@example.com")
)
```

Missing fields remain absent and are decoded according to their `FieldInput`. Initial forms decode
immediately, may be invalid, and start with used-only visibility containing no used addresses.

Initial assignments from another root or row group do not compile. Duplicate assignments for one
logical address are rejected rather than silently selecting one.

### Programmatic Updates

Typed updates encode the field's `Input` and rebuild the whole form:

```scala
form.updated(Email, "grace@example.com")
```

Raw updates are explicit:

```scala
form.updatedRaw(Email, Vector("first", "duplicate"))
```

Updates preserve unrelated values and interaction state, replace stale validation with a complete
new decode, and do not imply user interaction.

Repeated scalar helpers may remain as typed conveniences when their `Input` is a collection:

```scala
form.appended(Tags, "scala")
form.removedAt(Tags, 0)
```

These operations are not reused for repeated records.

### Rebuilding From Values

A definition can rebuild a pristine or interaction-preserving form from its own `FormValues`.
Cross-definition values are rejected using the private schema identity.

This operation supports reset, workflow rebasing, tests, and server-created canonical values without
exposing a constructor that accepts an independently supplied validation result.

## Validation Errors

Errors are owner-scoped and address-based:

```scala
final case class FormError[Owner](
  address: FormAddress[Owner],
  issue: FieldIssue
)

final class FormErrors[Owner] private (...)
```

They preserve:

- declaration and row order;
- duplicate errors;
- row identity across reordering;
- machine-readable codes;
- exact association with field or group views.

Browser names and DOM IDs are derived when rendering. They are not the error identity.

## Interaction State

Interaction controls validation visibility and nothing else:

```scala
final case class FormInteraction[Owner] private (
  used: Set[FormAddress[Owner]],
  visibility: ErrorVisibility
)

enum ErrorVisibility:
  case UsedOnly
  case All
```

`UsedOnly` exposes errors for exactly the leaf addresses in `used`. Row and group views can report
aggregate use when any known child field is used. Structural hidden controls never make a row used.

`All` makes every current error visible without manufacturing a set of all possible addresses.

Interaction is not included in `FormValues` equality and does not increment an edit revision in the
optional workflow.

## Typed Events

A typed event contains an already rebuilt form and separate protocol context:

```scala
enum FormEventKind:
  case Changed
  case Submitted
  case Recovered

final class FormEvent[Owner, Schema, Domain] private[scalive] (
  val form: Form[Owner, Schema, Domain],
  val data: FormData,
  val kind: FormEventKind,
  val meta: FormEventMeta
)
```

`FormEventMeta` contains target, submitter, and protocol-specific diagnostics. Event kind is a closed
sum rather than independent `submitted` and `recovery` booleans.

The definition hides the full event type:

```scala
enum Msg:
  case Changed(event: Definition.Event)
  case Submitted(event: Definition.Event)
```

Handling no longer repeats decoding or reconstructs state:

```scala
case Msg.Changed(event) =>
  ZIO.succeed(model.copy(form = event.form))
```

Event visibility semantics are:

| Event kind | Resulting visibility |
| --- | --- |
| `Changed` | `UsedOnly`, derived from `_unused_*` markers. |
| `Submitted` | `All`. |
| `Recovered` | `UsedOnly`, derived from ordinary `_unused_*` controls in the recovery payload. |

Recovery remains event provenance, not durable draft storage or persistence state.

The full payload remains on the event for debugging. `event.form.values` is the canonical editable
projection.

## Rendering Fields

A field view retains both raw editable values and successful typed projections:

```scala
final class FormFieldView[Owner, Input, Value] private[scalive] (...)
```

It exposes conceptually:

```scala
field.address
field.name
field.id
field.rawValues
field.input
field.result
field.errors
field.visibleErrors
field.isUsed
field.validationAttributes
```

Controls render from raw values so invalid text and structural duplicate input are never replaced by
a decoded or normalized domain value.

A scalar control helper renders the last raw value, matching the existing scalar browser convention.
If several raw values caused a duplicate-value error, `rawValues` still exposes all of them for
diagnostics or a custom renderer. The next ordinary browser event may collapse that malformed vector
to the one value representable by the rendered scalar control; that is a real `FormValues` change.

Typed control helpers remain convenience renderers rather than validation authorities. Low-level
HTML controls and browser names remain available when a helper does not fit.

Programmatic replacement of a focused browser input still requires Phoenix's
`phx-patch-focused` behavior. The form model does not override client focus semantics.

## Repeated Records

Repeated records are a schema feature distinct from repeated scalar values.

### Group Definition

A repeated group creates a second singleton owner for row field templates:

```scala
val Qualifications = ApplicationRoot.rows("qualifications")

val Title =
  Qualifications
    .text("title")
    .map(_.trim)
    .required(FieldIssue("Title is required"))

val AwardedOn =
  Qualifications
    .text("awarded_on")
    .emap(parseYear)

val QualificationRows =
  Qualifications.product[Qualification](
    (Title, AwardedOn)
  )

val Definition =
  ApplicationRoot.product[Application](
    (ApplicantName, QualificationRows)
  )
```

`QualificationRows` is a `FormPart` whose successful value is `Vector[Qualification]`. Row keys do
not enter the domain product.

The row-group owner prevents a field from another group from being used in the row product, row
view, initialization, or structural update.

### Stable Row Keys

Each group exposes a key type tied to that exact group:

```scala
opaque type FormRowKey[Group] = String

final class RepeatedGroup[Owner] private[scalive] (...):
  self =>
  type Key = FormRowKey[self.type]

val key: Qualifications.Key = ???
```

The browser encoding is constrained, bounded, reversible, and safe inside a bracket path and DOM
identity. Client-supplied text is validated before becoming a key.

Keys identify UI rows only. They are not database identifiers and confer no authorization.

Core row operations require the caller to supply a key:

```scala
form.added(QualificationRows, key)(
  Title.initial(""),
  AwardedOn.initial("")
)
```

An effectful convenience may generate a random key:

```scala
FormRowKey.random[Qualifications.type]
```

The pure form operation itself never reads randomness or global mutable state.

### Core Browser Representation

The core renderer uses stable keys directly in browser paths:

```text
application[qualifications][row_7f3a][title]
application[qualifications][row_7f3a][awarded_on]
```

Each rendered row includes a hidden `_scalive_row` presence control:

```text
application[qualifications][row_7f3a][_scalive_row]=1
```

The `_scalive_` prefix is reserved at every typed schema scope. Schema construction rejects an
application field using that prefix. Presence-control encounter order defines row order and ensures
that a row with no other successful controls remains representable. Projection converts these
controls into row structure and excludes them from `FormValues` field entries and interaction state.

The path segment is the only row identity. The marker value is the constant `"1"` and must match
exactly; any other value produces a group structural error. Each row path must contain exactly one
presence control.

Leaf fields submitted under a key without a valid row-presence control produce a group structural
error. Duplicate row-presence keys also produce a group error. A leaf may precede its presence
control in the payload because projection validates the complete payload before decoding rows. Every
path and row count is bounded.

### Decoding

Repeated-group decoding must:

- discover rows in row-presence order;
- validate row-key syntax and uniqueness;
- retain each recognized raw leaf vector;
- run every field in every row;
- accumulate errors in row order and then field declaration order;
- return `Vector[RowDomain]` only when every row succeeds;
- place row errors at stable logical row addresses;
- place collection errors at the group address;
- preserve invalid rows for rendering;
- ignore unknown well-formed fields while retaining them in `FormData`;
- apply explicit maximum row and value limits.

The whole form may be invalid while every row remains independently visible through row views.

### Row Views

Rows are rendered from current form values, not from the successful whole-form domain result:

```scala
form.rows(QualificationRows).splitBy(_.key) { (_, row) =>
  val title     = row.field(Title)
  val awardedOn = row.field(AwardedOn)

  div(
    title.text(title.validationAttributes),
    title.errorFeedback(renderError),
    awardedOn.text(awardedOn.validationAttributes)
  )
}
```

A row view exposes conceptually:

```scala
row.key
row.address
row.result
row.errors
row.visibleErrors
row.isUsed
row.field(Title)
row.bind(Title)
```

`row.bind(Title)` is a stable bound-field handle for programmatic updates. Its logical address
contains the group and row key.

Rendering uses `row.key` as the keyed `Signal.splitBy` identity so reordering retains DOM nodes,
bindings, focus, and row-local rendering state.

### Structural Operations

Structural operations are keyed and atomic:

```scala
form.added(QualificationRows, key)(assignments*)
form.removed(QualificationRows, key)
form.movedBefore(QualificationRows, key, target)
form.movedAfter(QualificationRows, key, target)
```

Each operation:

- updates the complete group subtree;
- preserves unrelated values;
- preserves interaction for retained rows;
- removes interaction and errors for removed rows through revalidation;
- initializes new rows as unused;
- reruns the complete enclosing form definition;
- rejects missing or duplicate keys explicitly;
- never interprets a mutable position as identity.

Moving a row changes `FormValues` because row order is user-controlled value state. Moving it does
not change its key or logical error addresses.

### Row Initialization

Initial and added rows use group-owned typed assignments. An indicative shape is:

```scala
QualificationRows.row(key)(
  Title.initial(existing.title),
  AwardedOn.initial(existing.awardedOnText)
)
```

The concrete builder syntax should be selected through compile-only API tests. It must preserve
group ownership and reject duplicate row field assignments.

### Nested Groups

The logical-address and value-tree representations must permit deeper nested groups. The first
implementation may limit public construction to repeated groups directly under a root, but it must
not encode root-only assumptions into addresses, errors, or values.

## Phoenix Nested-Parameter Adapter

Phoenix compatibility is a boundary adapter rather than core group behavior:

```scala
PhoenixNestedParamsAdapter
```

It translates between Phoenix conventions:

```text
items[0][name]
items[0][_persistent_id]
items_sort[]
items_drop[]
```

and stable core row keys and order.

The adapter owns:

- positional index parsing;
- index-to-key reconciliation;
- `_persistent_id` handling;
- sort and drop controls;
- the empty drop control;
- Phoenix's new-row convention;
- duplicate, unknown, and malformed index handling;
- translation of `_target` paths;
- translation of indexed `_unused_*` markers into stable logical row addresses;
- recovery translation using the same index, persistent-ID, and used-marker mapping;
- compatibility rendering helpers.

Phoenix's conventional `"new"` sort entry has no stable key. The adapter therefore receives an
explicit compatibility key allocator and a group-specific blank-row constructor. Its default
allocator deterministically chooses unused compatibility keys from submitted `_persistent_id`
values and allocates a distinct key for every `"new"` occurrence. The next render emits each
allocated key as `_persistent_id`, after which recovery and later events retain it. This allocation
is adapter policy and does not change the core rule that `form.added` requires a caller-supplied key.

Replaying one payload through the same adapter configuration must produce the same allocated keys.
Duplicate `"new"` entries create distinct rows in encounter order. Adapter limits bound how many new
rows one event may request.

Every submitted indexed row must contain exactly one syntactically valid `_persistent_id`, except a
row created from the current event's `"new"` convention. Persistent IDs must be unique across the
group. Missing, duplicate, or malformed values produce bounded group structural errors rather than
silently allocating replacement identities. If a client changes one valid persistent ID to another
between events, the next payload represents a different UI row identity; the old row disappears and
the new row is validated normally. These IDs still provide no persistence authorization.

Phoenix indexes never become core row identity or error identity. Posted persistent IDs are
identity-bearing adapter input, not authorization or persistence commands.

Ecto-specific association behavior, preload scope, `on_replace`, and database action inference do
not belong in this adapter or in the generic form schema.

## HTTP Forms

Ordinary HTTP form handling continues to decode bounded URL-encoded `FormData` after CSRF
validation. The typed decoder should accept a `FormDefinition` rather than only a low-level codec.

It can return either:

- a submitted `Definition.Form`, preserving invalid values for rerendering; or
- a successful domain value through an explicit convenience built on that form.

CSRF controls are validated at the transport boundary and excluded from `FormValues`.

Multipart forms and file ownership remain a separate transport concern.

## Testing Adapters

Connected and disconnected form test helpers should retain explicit successful-control payloads.
Typed helpers should additionally accept a definition and return its typed event or form.

Test APIs must support:

- duplicate names and exact order;
- malformed values;
- `_unused_*` markers;
- event kind and target;
- stable repeated-row keys;
- Phoenix indexed compatibility payloads;
- ordinary HTTP CSRF behavior;
- recovery payloads.

They must not silently pass through a `Map[String, String]`, which loses duplicates and ordering.

## Optional Form Workflow

Baseline and save behavior is useful but not fundamental. It belongs in an optional pure utility,
tentatively named `FormWorkflow`.

### State

```scala
final class FormWorkflow[Owner, Schema, Domain, Failure] private (
  val current: Form[Owner, Schema, Domain],
  val baseline: FormValues[Owner, Schema],
  val revision: FormRevision,
  val save: FormSaveState[FormSubmission[Owner, Schema, Domain], Failure],
  private val nextSubmissionGeneration: Long
)
```

The concrete type should also retain exact definition identity through path-dependent aliases.

The workflow exposes:

```scala
workflow.current
workflow.baseline
workflow.isDirty
workflow.revision
workflow.save
```

Dirty state is:

```scala
workflow.current.values != workflow.baseline
```

### Submission Snapshot

A valid form can produce an immutable snapshot:

```scala
final case class ValidFormSnapshot[Owner, Schema, Domain] private[scalive] (
  values: FormValues[Owner, Schema],
  value: Domain
)
```

Only the owning definition can create this value, and only from a valid form. A save attempt combines
that snapshot with workflow identity:

```scala
final case class FormSubmission[Owner, Schema, Domain] private (
  token: FormSubmissionToken,
  revision: FormRevision,
  snapshot: ValidFormSnapshot[Owner, Schema, Domain]
)
```

The token identifies the workflow instance and save generation. The revision identifies which
current edit state was submitted. Both are required: revision comparison preserves later edits,
while the token rejects an out-of-order or cross-workflow completion.

`FormSubmission` may expose `values` and `value` as convenience accessors delegated to its valid
snapshot.

### Save State

An indicative state model is:

```scala
enum FormSaveState[+Submission, +Failure]:
  case Idle
  case Saving(submission: Submission)
  case Failed(submission: Submission, failure: Failure)
```

The baseline represents the most recently acknowledged persisted values. A separate last-success
status is unnecessary unless a concrete UI requires it.

### Operations

The utility provides pure transitions conceptually equivalent to:

```scala
workflow.updated(event.form)
workflow.reset
workflow.beginSave
workflow.saveSucceeded(token)
workflow.saveSucceeded(token, canonicalSnapshot)
workflow.saveFailed(token, failure)
workflow.saveCancelled(token)
```

`beginSave` succeeds only when the current form is valid. An invalid attempt returns a workflow whose
current form has all errors visible, but does not create a persistence submission.

The workflow stores a stable identity and monotonic submission generation. Every attempt receives a
new token, including a retry at the same edit revision. Token equality includes both the workflow
identity and generation; tokens are never derived only from `FormRevision`.

`beginSave` rejects an attempt while another save is in flight and returns an observable
`AlreadySaving` result. The utility does not claim to make overlapping persistence operations safe.
Applications requiring concurrent saves must use optimistic versions, idempotency keys, or strict
serialization outside this utility.

### Revisions

The edit revision increments only when `FormValues` changes. Changes to used fields, error
visibility, target metadata, or save state do not increment it.

### Reset

Reset rebuilds a pristine form from the exact baseline values and row identities and clears
interaction state. It is available from `Idle` and `Failed` states.

Reset is blocked while a save is in flight. The application must first receive a matching
`saveCancelled` or terminal completion. An explicitly unsafe abandon operation may invalidate local
workflow state, but its name and documentation must state that it cannot prevent an external effect
from committing.

### Success

A matching success without canonical values acknowledges the submitted values as the new baseline.

A matching success with a canonical snapshot acknowledges that snapshot's values as the new
baseline. The snapshot type proves that the values belong to the same definition and decode
successfully. Invalid or cross-definition values cannot be passed as a successful acknowledgement.

If the current revision still equals the submitted revision, current values are replaced with the
acknowledged values and interaction becomes pristine.

If the current revision is newer, current form values and interaction are preserved. Only the
baseline advances. The preserved values are then re-evaluated against that baseline and may be clean
if they happen to equal the acknowledged canonical values.

The utility does not attempt a field-level merge between canonical saved values and newer edits.
That requires an application-specific policy.

### Failure

A matching failure preserves current form values, baseline, and interaction. It retains the failure
with the exact submitted snapshot.

If newer edits exist, the failure still describes the submitted snapshot. It is not automatically
inserted into the current form's field errors. The application may display it as save-level status or
translate it only after verifying that it still applies.

Only a matching `Saving` token accepts a success, failure, or cancellation. Unknown, stale,
duplicate, and already-failed tokens cannot modify workflow state. The transition API should make
that outcome observable as `Stale` or an equivalent result rather than silently pretending the
completion was applied.

### Async Integration

`FormWorkflow` remains pure. Applications run persistence through existing LiveView facilities:

```scala
ctx.async.start(SaveKey)(save(submission.value))(result =>
  Msg.SaveCompleted(submission.token, result)
)
```

`AsyncKey` can interrupt a task and suppress an obsolete completion. Neither `AsyncKey` nor a form
submission token can roll back an external commit that already happened. Persistence must provide
the required serialization, optimistic version, transaction, or idempotency guarantee. The form
submission token protects only local workflow transitions. The edit revision separately protects
changes made while the accepted save is running.

### Policies Left To Applications

Applications continue to own:

- persistence effects;
- database constraints;
- optimistic locking and conflict representation;
- retry and backoff;
- redirect and notification behavior;
- server-error-to-field-error translation;
- canonical domain-to-input projection;
- partial-field or normalized dirty equality;
- durable drafts and collaborative merging;
- concurrent persistence and cross-process save ordering.

## End-To-End Example

The following sketch shows how the layers fit together. It is illustrative rather than a frozen
surface syntax.

```scala
final case class Qualification(title: String, awardedOn: Year)

final case class Application(
  applicantName: NonEmptyString,
  qualifications: Vector[Qualification]
)

object ApplicationForm:
  val Root = FormRoot("application")

  val ApplicantName =
    Root
      .text("applicant_name")
      .map(_.trim)
      .required(FieldIssue("Applicant name is required"))
      .emap(value =>
        NonEmptyString
          .from(value)
          .left
          .map(message => FieldIssues.one(FieldIssue(message)))
      )

  val Qualifications = Root.rows("qualifications")

  val Title =
    Qualifications
      .text("title")
      .map(_.trim)
      .required(FieldIssue("Title is required"))

  val AwardedOn =
    Qualifications
      .text("awarded_on")
      .emap(parseYear)

  val QualificationRows =
    Qualifications.product[Qualification]((Title, AwardedOn))

  val Definition =
    Root.product[Application]((ApplicantName, QualificationRows))
```

A LiveView model can store either `Definition.Form` directly or the optional workflow:

```scala
final case class Model(
  editor: ApplicationForm.Definition.Workflow[SaveFailure]
)
```

The definition alias hides root and schema identity while retaining the domain and failure types.

Event handling remains explicit:

```scala
case Msg.Changed(event) =>
  ZIO.succeed(model.copy(editor = model.editor.updated(event.form)))

case Msg.Submit(event) =>
  model.editor.updated(event.form).beginSave match
    case FormSaveStart.Invalid(next) =>
      ZIO.succeed(model.copy(editor = next))

    case FormSaveStart.AlreadySaving(current, _) =>
      ZIO.succeed(model.copy(editor = current))

    case FormSaveStart.Started(next, submission) =>
      ctx.async
        .start(SaveKey)(repository.save(submission.snapshot.value))(result =>
          Msg.SaveCompleted(submission.token, result)
        )
        .as(model.copy(editor = next))
```

## Security And Resource Bounds

All form input remains untrusted, including typed row keys and hidden fields.

The implementation must:

- bound HTTP and WebSocket form payload sizes;
- bound field value counts;
- bound repeated-row counts;
- bound path depth and segment length;
- bound row-key length;
- reject malformed path and key encodings without throwing defects;
- reject duplicate schema addresses;
- reject duplicate row identities;
- keep persisted IDs separate from UI keys;
- require application authorization for every referenced persisted entity;
- preserve unknown raw payload entries for diagnostics without treating them as schema values.

Typed decoding is validation and ergonomics, not an authorization boundary.

## Migration Plan

The redesign should be implemented in dependency order. Temporary internal adapters are acceptable
while keeping the repository green, but the completed alpha API should not retain obsolete public
wrappers without a concrete external compatibility requirement.

1. Add compile-only API contract tests for fields, products, events, repeated groups, and workflows.
2. Rename the current single-name `FormValues` wrapper.
3. Replace lossy path parsing with strict round-trip-safe parsing and explicit array segments.
4. Add owner-scoped logical addresses, group-scoped row keys, and injective DOM ID encoding.
5. Introduce canonical `FormValues` and exhaustively specify payload projection and equality.
6. Implement `FieldInput` and `FormField[Owner, Input, Value]` with field-level `emap`.
7. Add `FormPart`, heterogeneous tuple recursion, and `Mirror.ProductOf` construction.
8. Replace publicly constructible state with privately consistent `Form` instances.
9. Change typed bindings to produce events containing a ready rebuilt form and closed event kind.
10. Migrate scalar rendering, typed initialization, typed updates, and raw escape hatches.
11. Implement repeated-group schemas, row views, validation, and keyed structural operations.
12. Implement Phoenix indexed nested-parameter translation as a separate adapter.
13. Migrate `FormDynamicInputsLiveView` to the repeated-group API.
14. Update ordinary HTTP decoding and connected/disconnected form test adapters.
15. Implement `FormWorkflow` as an independent optional utility.
16. Remove fixed-arity product overloads, nested public `zip` construction, unchecked constructors,
    and obsolete legacy fallbacks.
17. Update all guides, examples, API snapshots, and compatibility documentation.
18. Run the complete native test suite and upstream Phoenix LiveView end-to-end form tests.

## Acceptance Criteria

The architecture is complete when all of the following are true:

- A string field refines into a domain type through field-level `emap`.
- The refined field still accepts its original typed editable input for initialization and updates.
- Malformed duplicate scalar input remains renderable and produces a structural field error.
- Scalar helpers render the last duplicate raw value while retaining the complete raw vector.
- A six-field case class needs no special overload.
- A product with more than 22 fields compiles through the same API.
- Product type, arity, and order mismatches fail compilation.
- Duplicate schema paths fail at schema construction.
- Schema fields using the reserved `_scalive_` prefix fail at schema construction.
- Independent errors accumulate in declaration order.
- Metadata-only and interaction-only changes do not affect `FormValues` equality.
- Unknown well-formed controls do not enter `FormValues` or invalidate the typed form.
- Malformed browser names produce root structural errors and remain available in `FormData`.
- Invalid visible text participates in dirty equality.
- Repeated scalar order participates in dirty equality.
- Repeated records decode without parallel application arrays.
- Every row has a stable group-scoped key.
- Reordering retains row errors, used state, DOM identity, bindings, and focused controls.
- Row-specific errors remain attached to the same key after reordering.
- Removing and adding an identical-looking row remains dirty because the key changed.
- Rows with no ordinary successful controls survive through their hidden presence control.
- A row-presence marker with a nonconstant value or duplicate occurrence fails structurally.
- Duplicate and malformed row keys produce bounded validation failures.
- Recovery reconstructs stable rows and interaction visibility.
- Phoenix sort, drop, and `_persistent_id` behavior passes upstream compatibility tests.
- Missing, malformed, and duplicate Phoenix persistent IDs fail structurally.
- Changing a valid Phoenix persistent ID creates a different UI row identity and `FormValues`.
- Phoenix indexed used markers and targets resolve to stable row addresses after reordering.
- Multiple Phoenix `"new"` entries receive distinct deterministic compatibility keys.
- Typed events expose a ready form while keeping protocol metadata separate.
- No public constructor can pair unrelated values and validation results.
- Reset restores exact baseline values, row order, row keys, and pristine interaction.
- Invalid forms can be dirty and reset without a decoded domain value.
- Save failure retains the submitted snapshot and current edits.
- Save success replaces an unchanged current form with acknowledged canonical values.
- Save success preserves edits made after submission and advances only the baseline.
- Stale save success and failure cannot alter workflow state.
- A second save is rejected while one is in flight.
- Retries at one unchanged edit revision receive distinct submission tokens.
- Reset is blocked during an in-flight save unless cancellation is confirmed.
- An invalid or cross-definition canonical acknowledgement cannot update the workflow.
- Tests distinguish local token safety from persistence ordering and optimistic-lock requirements.
- Existing raw form bindings remain available as explicit escape hatches.

## Deferred Surface Details

The following details should be settled through compile-only API tests and focused prototypes without
changing the architecture:

- final names for `FieldInput`, `FormPart`, and `FormWorkflow`;
- tuple literal versus `*:` spelling for one-field and empty products;
- the exact row-initialization builder syntax;
- whether stale workflow completions return an enum, `Either`, or a result with an `applied` flag;
- the concrete non-empty collection used by `FieldIssues` and `FormErrors`;
- the encoded alphabet and maximum length for browser row keys;
- whether first-release public groups may nest inside rows;
- optional conventional field derivation after the explicit API is proven.

These are API ergonomics decisions. They must not collapse transport data, semantic values,
interaction state, validation, row identity, or persistence workflow back into one type.
