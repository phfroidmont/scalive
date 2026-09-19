{%
title = "Typed forms and validation"
description = "Decode rooted browser forms into domain values with normalization, recovery, richer controls, and accessible feedback."
order = 11
section = guides
group = "Interfaces and input"
%}

## Before You Start {#prerequisites}

You need a `LiveView` with a typed model and messages, and you should be able to
render controls and bind browser events. Review [Models and messages](../learn/models-and-messages.md)
or [HTML and event bindings](html-dsl-and-event-bindings.md) if either part is
not yet familiar.

For a first pass, follow the definition, model, event, rendering, and reset
sections through the [typed profile form](../examples/profile-form.md). Return
for repeated rows and save coordination when the screen needs them. The runnable
examples form this progression:

1. [Typed profile form](../examples/profile-form.md) for one complete validated form.
2. [Stable repeated contact rows](../examples/repeated-contacts-form.md) for keyed row operations.
3. [Form save workflow](../examples/form-save-workflow.md) for dirty state and correlated persistence.

## Define A Rooted Form {#define-a-rooted-form}

Start with a @:apiSymbol(class:scalive.FormRoot)`FormRoot`@:@, define fields relative
to it, and combine those fields into one domain constructor:

```scala
final case class Profile(name: String, email: String)

object Profile:
  val Root = FormRoot("profile")

  val Name = Root
    .text("name")
    .map(_.trim)
    .required(FieldIssue("validation.name.required", Some("required")))

  val Email = Root
    .text("email")
    .map(_.trim)
    .required(FieldIssue("validation.email.required", Some("required")))
    .validate(FieldIssue("validation.email.invalid", Some("invalid_email")))(
      EmailPattern.matches
    )

  val Definition = Root.product[Profile]((Name, Email))
```

The stable root value gives each field a complete browser name such as
`profile[name]`. Its singleton owner type prevents fields from another root,
even one with the same runtime name, from being combined accidentally. The
constructor produces `Profile` only when every field decodes successfully.

Normalize before validating. Here @:apiSymbol(def:scalive.FormField.map)`map`@:@
runs before @:apiSymbol(def:scalive.FormField.required)`required`@:@, so
whitespace-only input is blank and valid values enter the domain without
surrounding whitespace. A `FieldIssue` carries the stable message or
localization key and an optional machine-readable code.

## Accumulate Field Errors {#accumulate-field-errors}

Fields combined by @:apiSymbol(def:scalive.FormRoot.product)`FormRoot.product`@:@
accumulate independent decoding errors in field order. An invalid name and
email therefore produce both path-specific errors rather than stopping after
the first field. The tuple arity and element types must exactly match the case
class constructor, so missing fields and differently typed positions are
rejected at compile time. When adjacent case-class fields have the same type,
their semantic order remains the caller's responsibility.

Use `text` for a scalar that treats absence as its default and rejects
duplicates, `optionalText` for `Option[String]`, and `texts` for repeated
values. Use `Root.field("enabled", FieldInput.checkbox())` for a Boolean
checkbox, or supply a custom `FieldInput` to own decoding and encoding.
Then compose normalization and domain refinement with
`map`, `required`, `validate`, and `emap`:

```scala
val Age = Root.text("age").emap { text =>
  text.toIntOption.filter(_ >= 0).toRight(
    FieldIssues.one(FieldIssue("validation.age.invalid", Some("invalid_age")))
  )
}
```

`emap` preserves the editable input type (`String` here) while refining the
successful value to another type (`Int`). This is why typed updates can still
encode browser-editable input without requiring an inverse for every domain
refinement.

Use `FormDefinition.emap` after product construction when validation depends on
more than one field. Return errors from the pre-refinement definition so every
issue still has an owner-checked logical address:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-cross-field-validation)

Each error retains its owner-scoped @:apiSymbol(class:scalive.FormAddress)`FormAddress`@:@,
allowing rendering to associate feedback with the exact input. The complete executable
profile definition also limits biography length:

@:sourceRegion(documentation/site/src/scalive/docs/examples/ProfileFormExample.scala, profile-form-example)

## Keep Form State In The Model {#keep-form-state-in-the-model}

Create pristine form state during mount and store the definition's path-dependent
`Form` alias in the model:

`Task[A]` is the effect returned from LiveView lifecycle methods, and
`ZIO.succeed` creates one that cannot fail.

```scala
final case class Model(form: Profile.Definition.Form)

def mount(ctx: MountContext): Task[Model] =
  ZIO.succeed(Model(Profile.Definition.initial()))
```

Pass owner-checked typed initial values when editing existing data:

```scala
Profile.Definition.initial(
  Profile.Name.initial(existing.name),
  Profile.Email.initial(existing.email)
)
```

@:apiSymbol(def:scalive.FormDefinition.initial)`FormDefinition.initial`@:@ decodes
the initial editable values immediately, so state may contain required-field errors.
Those errors are intentionally not visible yet: the form has not been submitted
and no field is used.

Store the already rebuilt form from every typed event. `Definition.Event` is the
matching event alias, so model and message declarations stay tied to this exact
schema:

```scala
enum Msg:
  case Validate(event: Profile.Definition.Event)
  case Save(event: Profile.Definition.Event)

case Msg.Validate(event) =>
  ZIO.succeed(model.copy(form = event.form, saved = None))
```

`FormValues` is the immutable, canonical, metadata-free editable state. It
contains schema-owned scalar values and stable keyed rows, but not event target,
used-field, or submission metadata. Values from different definition instances
are intentionally unequal. Compare values to a baseline for dirty checks, and
use `Definition.fromValues(values)` when interaction should become pristine or
`fromValues(values, interaction)` when deliberately preserving it.

## Update Fields Programmatically {#update-fields-programmatically}

Use @:apiSymbol(def:scalive.Form.updated)`Form.updated`@:@ when a server-side action
needs to replace one field with its typed editable input without rebuilding
@:apiSymbol(class:scalive.FormData)`FormData`@:@ or validation state manually:

```scala
val updatedForm = profileForm.updated(
  Profile.Name,
  "Grace Hopper"
)
```

The input type comes from the field's `FieldInput`: `String` for `text`,
`Option[String]` for `optionalText`, `Vector[String]` for `texts`, and `Boolean`
for `checkbox`. Use
`updatedRaw(field, Vector(...))` only when raw browser values are explicitly
needed, such as malformed-control tests. The complete form is decoded again
after every update, while its interaction state is preserved.

For fields submitted repeatedly under one exact name, use
@:apiSymbol(def:scalive.Form.appended)`appended`@:@ to add one value and
@:apiSymbol(def:scalive.Form.removedAt)`removedAt`@:@ to remove one by its position among that
field's values. These operations do not add or remove indexed nested rows whose controls use several
different names.

Phoenix normally preserves browser-managed state for a focused control during DOM patches. Add
`phx.patchFocused := true` to a control when a programmatic form update must replace its visible
value while it remains focused.

## Handle LiveView Change And Submit Bindings {#handle-change-and-submit-events}

Bind both LiveView events through the rooted form:

```scala
form(
  idAttr := "profile-form",
  profileForm.onChange(Msg.Validate(_)),
  profileForm.onSubmit(Msg.Save(_)),
  // fields and actions
)
```

`onChange` installs Phoenix's `phx-change` binding, not the native HTML `onchange`
attribute. The LiveView client observes both browser `input` and `change` events, so
text fields normally produce change messages while the user edits. Because the binding
is on the form, each message contains the form's successful controls and identifies the
changed control. See [HTML and event bindings](html-dsl-and-event-bindings.md#bind-events-to-messages)
for the distinction between LiveView bindings and native event attributes.

Both messages carry `Profile.Definition.Event`. `event.form.result` is either
owner-scoped accumulated @:apiSymbol(class:scalive.FormErrors)`FormErrors`@:@ or
the decoded `Profile`. `event.kind` distinguishes `Changed`, `Submitted`, and
`Recovered`; `event.meta` contains the logical target, optional raw submitter metadata, browser
target, metadata, and diagnostics. The standard Phoenix browser client includes a named submit
control in `event.data` rather than `event.rawSubmitter`; use a definition-owned typed submitter for
application action routing. Submitted events rebuild a form with all errors
visible, while change and recovery events show errors only for used fields.
Use `RawFormEvent[A]` only for an explicitly low-level
`on.change.form(formCodec)` or equivalent `FormCodec` binding; definition-backed
events should use `Definition.Event` and `event.form`.

Use `event.meta.target` when work should be narrowed to the logical field that
changed. It is a `FormAddress`, not an unchecked browser string:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-event-target)

### Choose When Feedback Appears {#choose-when-feedback-appears}

For signal-backed forms, `formSignal.bind` ties rendering and events to one stable,
instance-unique `DomRef`. Its default `FormFeedback.WhenUsed` policy follows Phoenix
unused markers. Choose `FormFeedback.AfterBlur` when an invalid field should remain
quiet until focus leaves it. Include `binding.modifiers` exactly once when supplying
your own `form` tag: under `AfterBlur`, the modifiers include the nameless hidden child
required by blur transport. `binding.render(...)` also rejects overrides of its owned
ID and event bindings. Keep the feedback policy stable for a rendered form instance;
the binding is the policy's configuration point.

Apply every `FormUpdate` to the form in the **current** model, not to a form captured
during rendering. `AppliedFormUpdate.valuesChanged` is false for feedback-only
transitions, so status such as save confirmation need not be cleared just because a
field blurred. Submission remains a separately typed `Definition.Event`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/BlurFeedbackExample.scala, blur-feedback-example)

A bound control's `id` and feedback ID are scoped by the form `DomRef`; use its `id`,
`validationAttributes`, and `errorFeedback` together for labels and ARIA. For a custom
scalar input, `inputAttributes` supplies its retained `id`, `name`, value, and blur
wiring. Calling `.onBlur(value => Msg.NormalizePhone(value.trim))` composes an
application message after feedback dispatch, but command order does not wait for
server acknowledgement. It is therefore not a race-free normalization mechanism.
There is no `normalizeOnBlur` API. A later full-form snapshot can still overwrite a
server-normalized value before its patch reaches the browser; this feedback API does
not provide a general value-reconciliation guarantee.

For a composite control, the widget owns its logical focus boundary and dispatches
`control.blurred` only when focus leaves the whole widget. In keyed rendering, bind a
row control with `binding.field(rows, key, field)` so blur state follows the stable row
key. A bound row control must only be evaluated while its row exists. Outside a keyed
loop, use `binding.optionalField(rows, key, field)(control => ...)` or the overload
accepting a `BoundFormField`; it omits the control safely when the row disappears.

`form.pristine` clears interaction while retaining current values, validation errors,
and the active feedback policy. `Definition.fromValues(form.values)` revalidates with
default pristine interaction; the binding reapplies its configured policy.
`Definition.initial(...)` creates fresh values and interaction. A fresh mount starts
with no blur history. Applying a recovery update to retained server state preserves
its history; applying it to a freshly mounted form restores values but not old blur
history. Submission makes all feedback eligible until reset.

Within a `LiveComponent`, use the usual `phx.target(self)` on the form and on controls
with application-specific blur callbacks. Scalar `.onBlur` uses the browser's `value`,
or an empty string when absent; it is not the complete value vector of a multi-select.

Feedback-only operations (`pristine` and `withAllErrorsVisible`) preserve malformed
payload errors rather than silently dropping them through revalidation.

Phoenix `_unused_*` markers determine the used-field set on change and recovery.
Putting `phx.noUnusedField := true` on a form or control suppresses those markers,
so received fields are treated as used. Keep that switch exceptional; the
default gives field-local feedback before submit.

On submit, persist or pass the domain value only from the `Right` branch. Do not
re-decode strings manually in `handleMessage`:

```scala
case Msg.Save(event) =>
  event.form.result match
    case Right(profile) => save(profile).as(model.copy(
      form = event.form,
      saved = Some(profile)
    ))
    case Left(_) => ZIO.succeed(model.copy(
      form = event.form,
      saved = None
    ))
```

## Route Multiple Submit Actions {#route-multiple-submit-actions}

Use one definition-owned @:apiSymbol(class:scalive.FormSubmitter)`FormSubmitter`@:@ when several
native submit buttons send the same typed form to different operations. Give each enum case an
explicit wire value so renaming a Scala case does not silently change the browser contract:

```scala
enum Intent(val wireValue: String):
  case Discover extends Intent("discover")
  case Preview  extends Intent("preview")
  case Save     extends Intent("save")

val Submitter = Profile.Definition.submitter(Intent.values)(_.wireValue)
```

The default browser name is generated below the form root in Scalive's reserved namespace. Supply a
checked custom name only when an existing HTTP or Phoenix boundary requires one:

```scala
val CompatibleSubmitter =
  Profile.Definition.submitter(Intent.values, wireName = "action")(_.wireValue)
```

Bind the descriptor once and render each operation from the same mapping:

```scala
enum Msg:
  case Validate(event: Profile.Definition.Event)
  case Submit(
    event: Profile.Definition.Event,
    intent: Either[FormSubmitter.DecodeError, Intent]
  )

form(
  Profile.Definition.onChange(Msg.Validate(_)),
  Profile.Definition.onSubmit(Submitter)(Msg.Submit.apply),
  Submitter.button(Intent.Discover)("Discover"),
  Submitter.button(Intent.Preview)("Preview"),
  Submitter.button(Intent.Save)("Save")
)
```

The binding decodes exactly one value from lossless `FormData`. Missing, unknown, and duplicate
values remain distinct `DecodeError` cases. There is no implicit default for an Enter-triggered or
programmatic submission without a named submitter. Every decoded action remains untrusted and must
be authorized before performing state-changing work.

The action result and `event.form.result` are deliberately independent. A discovery operation can
inspect the rebuilt field state even when the complete domain product is invalid, while Save can
require `Right(profile)`. All buttons still trigger `FormEventKind.Submitted`, so all validation
errors become visible. Keep continuous validation on `onChange`; use an ordinary typed click message
for an operation that does not need the submitted form.

Use `Submitter.attributes(intent)` for a native submit `input`, a form-associated external control,
or other custom markup. The runnable [typed profile form](../examples/profile-form.md) demonstrates
Preview and Save buttons through the same descriptor.

## Recover A Form After Reconnect {#recover-a-form-after-reconnect}

Give a recoverable form a stable, unique DOM `id` and keep its change binding.
Phoenix normally recovers client form values through the change event after a
LiveView reconnect. Storing `event.form` restores the projected values and
validation state produced by the same definition.

Use a dedicated typed recovery message when recovery needs different behavior:

```scala
form(
  idAttr := "profile-form",
  profileForm.onChange(Msg.Validate(_), Msg.Recover(_)),
  profileForm.onSubmit(Msg.Save(_)),
  // controls
)

case Msg.Recover(event) =>
  ZIO.succeed(model.copy(form = event.form))
```

The recovery callback runs for successful and failed decoding. Inspect
`event.kind == FormEventKind.Recovered` when one message type handles multiple
event sources. Recovery is distinct from submission, so used-field visibility
still comes from recovered payload markers. Protocol details remain available in
`event.meta` without contaminating `event.form.values`.

Disable client auto-recovery explicitly when replay would be unsafe or the
server is authoritative:

```scala
form(
  idAttr := "payment-form",
  paymentForm.disableRecovery,
  paymentForm.onSubmit(Msg.Pay(_)),
  // controls
)
```

Current recovery is browser/LiveView protocol recovery, not durable draft
storage. It does not survive a deliberate page exit, replace a database-backed
draft, recover file input bytes, or merge concurrent edits. Persist drafts in an
application service when those guarantees are required.

## Model Repeated Rows {#model-repeated-rows}

Use a repeated group when each row has several fields and a stable identity:

```scala
final case class Phone(label: String, number: String)
final case class Contact(name: String, phones: Vector[Phone])

object Contact:
  val Root        = FormRoot("contact")
  val Name        = Root.text("name").map(_.trim)
  val Phones      = Root.rows("phones")
  val PhoneLabel  = Phones.text("label").map(_.trim)
  val PhoneNumber = Phones.text("number").map(_.trim).required(
    FieldIssue("validation.phone.required", Some("required"))
  )
  val PhoneRows  = Phones.product[Phone]((PhoneLabel, PhoneNumber))
  val Definition = Root.product[Contact]((Name, PhoneRows))
```

Initialize rows with `PhoneRows.initial(PhoneRows.row(key)(...))`. At render
time, `contactForm.rows(Contact.PhoneRows)` returns stable keyed row views. Each
core row must render its presence control, then can render bound field views:

```scala
contactForm.rows(Contact.PhoneRows).map { row =>
  val number = row.field(Contact.PhoneNumber)
  div(row.presence(), number.text(number.validationAttributes))
}
```

Use `added`, `removed`, `movedBefore`, and `movedAfter` with a
`FormRowKey[Contact.Phones.type]` for server-side row operations. Use
`row.bind(field)` with typed `updated` or explicit `updatedRaw` for a field in
one exact row. Stable keys, rather than display indexes, preserve row identity,
used state, and error addresses across reordering.

In a signal-backed keyed projection, call `row.presence()` on the retained
`Signal[FormRowView]`. The [stable repeated contact rows example](../examples/repeated-contacts-form.md)
shows complete initialization, rendering, typed events, add, remove, both move
directions, row-local validation, submission, and reset. Its order summary makes
the persistent keys visible while rows move.

The core wire shape is keyed and includes the presence control. To accept
Phoenix's indexed nested-parameter convention, adapt that boundary explicitly.
For this recipe the per-row mapping is
`contact[phones][0][_persistent_id]`, while the sibling array controls are
`contact[phones_sort][]` and `contact[phones_drop][]`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-phoenix-repeated-controls)

The bounded `PhoenixNestedParamsAdapter` translates the compatibility payload
into the same keyed form projection. `persistentIdName(index)` reconnects each
positional index to a stable key, `sortName` supplies order and requests `"new"`
rows, and `dropName` removes indexes. Keep an empty `dropName` control so
removing the last row still submits the group. `configured` accepts
deterministic key allocation and blank-row policy when its defaults do not
match the application. Prefer the core keyed controls for new Scalive
interfaces; use the adapter when interoperating with Phoenix-style clients or
markup.

## Render Richer Controls {#render-richer-controls}

Ask the form for a field view, then use its generated ID, name, current
raw value, and validation helpers:

```scala
val emailField = profileForm.field(Profile.Email)
val messages = Map(
  "validation.email.required" -> "Email is required.",
  "validation.email.invalid"  -> "Enter a valid email address."
)

label(forId := emailField.id, "Email")
emailField.email(emailField.validationAttributes)
emailField.errorFeedback { error =>
  messages(error.message)
}
```

`FormFieldView`, its signal-backed operations, and `FormControl` provide `text`,
`email`, `tel`, `date`, `search`, `password`, `hidden`, `checkbox`, `textarea`,
and `select` helpers. Normal attributes such as `autocomplete`, `maxlength`,
`required`, `multiple`, or CSS classes remain available:

```scala
val bioField  = profileForm.field(Profile.Bio)
val roleField = profileForm.field(Profile.Role)

bioField.textarea(rows := 6, bioField.validationAttributes)
roleField.select(
  List("reader" -> "Reader", "editor" -> "Editor"),
  roleField.validationAttributes
)
```

The native `tel`, `date`, and `search` helpers render retained raw scalar values;
they do not introduce parsing, trimming, or domain validation. Bound controls
keep their form-instance-scoped identity and blur wiring and reject overrides
of binding-owned attributes. Plain and signal-backed field views keep their
logical field identity and append caller modifiers without those binding
restrictions. On all three surfaces, ARIA feedback remains opt-in through
`validationAttributes`.

A checkbox is checked when its submitted value occurs in `rawValues`. Its
default checked value is `"true"`, or pass an explicit value. The helper does
not generate a hidden unchecked value. For a Boolean field, use
@:apiSymbol(def:scalive.FieldInput.checkbox)`FieldInput.checkbox()`@:@ to pair
strict decoding with typed initialization and updates:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-checkbox-input)

The codec decodes absence as `false` and exactly one `"true"` value as `true`.
Any other single value produces `invalid_checkbox`; multiple values produce
`duplicate_value`, even when identical. Encoding `false` omits the value, while
encoding `true` emits the checked token. Both issues are configurable through
`invalidIssue` and `duplicateIssue`.

Custom tokens must match on both sides: pair `FieldInput.checkbox("yes")` with
`control.checkbox("yes")`. Matching is exact, without trimming or case folding;
an explicitly configured empty token is distinct from absence. The default
codec rejects `"on"` and `"false"`, and does not accept hidden unchecked fallback
inputs. In change and submit payloads, omitted or disabled controls decode as
false; the codec does not preserve their previous values. A checkbox-only repeated row
still needs its `row.presence()` control to retain the row when unchecked.
Requiring a checked value is a separate semantic rule, expressible with
`field.validate(issue)(identity)`.

For repeated checkbox choices, use `Root.texts`, not the Boolean
`FieldInput.checkbox` codec. A bound `FormControl` provides
`item(key: String): FormControlItem[Msg]` so each checkbox has its own label
target while all items submit under the same field name:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-repeated-checkbox-items)

The recipe uses a static `"news"` token and a `Signal[String]` research token;
both `item.checkbox(checkedValue, mods*)` overloads require an explicit token.
Each item's `id` and `name` are `Signal[String]`. Here every name is exactly
`subscriptions[topics]`: no item key, index, or extra `[]` is appended. A
submission with no successful items decodes to `Vector.empty`.

An item key is an immutable, stable identity, independent of its submitted
token; it is not a signal. Arbitrary strings, including the empty string, are
allowed; `null` is rejected. Keys are encoded losslessly into ASCII IDs scoped
to the form instance, field, and repeated-row identity when present. The exact
ID format is private: use `item.id`, not a reconstructed ID. Keep keys unique
among items of the same rendered control; the caller owns that responsibility.

Checked state uses exact membership of the current token in the parent's
`rawValues`. Items with different keys but the same token are checked together;
their submitted values do not distinguish them. Changing a reactive token updates
the checkbox projection but does not rewrite those raw values or migrate an old
selection. Native successful-control
rules still apply: unchecked, disabled, and removed checkboxes are omitted from
the next form snapshot. There are no hidden unchecked fallbacks or automatic
preservation of omitted selections.

Use a `fieldset` and `legend` for the group and a separate label targeting each
item's ID. Pass the parent's `validationAttributes` to each checkbox explicitly
and render one parent `errorFeedback` for the group. Configure any application
`.onBlur(...)` callback on the parent **before** calling `.item(...)`. With
`FormFeedback.AfterBlur`, blurring **any** item makes the parent's feedback
eligible, including when focus moves to another item in the same group. This
is not whole-group blur; custom composite widgets still own that boundary.

For dynamic choices, use `.splitBy` with stable choice keys and create each item
inside the keyed rendering scope. Do not use changing tokens or display indexes
as identity. Choice keys distinguish checkboxes for one repeated-value field;
they are not form-row keys. Repeated form rows still use the row APIs and
`row.presence()` described above.

A select marks every option found in `rawValues`; for a `multiple` select, use a
repeated-value field such as `Root.texts`.

There are no current input rendering helpers for numeric, radio-group,
or file controls. Use `Root.field` plus ordinary HTML controls for custom
decoding, and use `liveFileInput` with the upload API for files. The existing
helpers preserve raw strings; they do not parse numbers or dates implicitly.

An unfocused native date input can receive an invalid `value` attribute from a
server update while exposing an empty DOM `input.value`. A later form snapshot
contains the browser's current value, not the original attribute, and can
replace the retained server value.
Use a text input when arbitrary malformed date text must remain editable and
visible.

A custom `FieldInput` defines both structural decoding and the inverse used by
typed server updates. The field view continues to render `fieldValue` from the
retained raw values rather than the decoded domain value. A text control can
therefore show malformed input even when decoding fails:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-custom-field-input)

Use the lower-level `FormCodec` only when a custom control or transport adapter
does not need definition-owned state, bounded projection, or interaction-aware
errors:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-raw-codec)

@:apiSymbol(def:scalive.FormFieldView.validationAttributes)`validationAttributes`@:@
connects the input to feedback and adds `aria-invalid` when visible errors
exist. @:apiSymbol(def:scalive.FormFieldView.errorFeedback)`errorFeedback`@:@
renders a stable live region whose errors remain hidden until that field is
used or the form is submitted. Scalive owns the feedback ID, live-region
attributes, visibility rules, and `form-errors` and `form-error` wrappers. The
renderer owns only each error's presentation, so `FormError.message` may remain
a stable localization key instead of pre-rendered text.

The signal-backed field accepts the same pattern and passes a retained error
signal to the renderer:

```scala
val emailField = profileFormSignal.field(Profile.Email)

emailField.errorFeedback { error =>
  error.map(value => messages(value.message))
}
```

Use a real `label` with `forId` and keep success feedback in a separate status
region.

## Coordinate Dirty State And Saving {#coordinate-form-workflow}

For simple screens, storing `Definition.Form` directly is enough. When a screen
also needs a baseline, dirty tracking, reset, and one in-flight save, wrap it in
the optional @:apiSymbol(class:scalive.FormWorkflow)`FormWorkflow`@:@:

```scala
final case class SaveError(message: String)

final case class Model(workflow: Profile.Definition.Workflow[SaveError])

val initialModel = Model(
  Profile.Definition.workflow(Profile.Definition.initial())
)

def prepareSave(model: Model, event: Profile.Definition.Event) =
  val changed = model.workflow.updated(event.form)

  changed.beginSave match
    case FormSaveStart.Invalid(next) =>
      model.copy(workflow = next) -> None
    case FormSaveStart.AlreadySaving(current, _) =>
      model.copy(workflow = current) -> None
    case FormSaveStart.Started(next, submission) =>
      model.copy(workflow = next) -> Some(submission)

val (nextModel, submission) = prepareSave(model, event)
install(nextModel)
submission.foreach(enqueueSave)
```

The state/effect boundary must install `nextModel` before dispatching
persistence, so even an immediate completion observes the matching `Saving`
workflow. The invalid branch renders `next.current` with all errors visible.

`isDirty` compares canonical `FormValues`. `isSaving` reports whether a save is
in flight; `beginSave` still enforces duplicate-submit rejection. When no save
is active, `reset` returns to the baseline.
Each save captures a valid value-and-values snapshot plus a token and revision.
Send the captured `FormSubmissionToken` through the persistence callback and
apply exactly one completion transition:

```scala
model.workflow.saveSucceeded(token) match
  case FormWorkflowTransition.Applied(next) => model.copy(workflow = next)
  case FormWorkflowTransition.Stale(_)       => model
```

Carry the captured token unchanged through the persistence callback. A
completion carrying a token from another save attempt is stale. Run the effect with
[`ctx.async.start`](async-work-and-subscriptions.md#run-finite-work-with-a-typed-key)
and close over that token in the completion-message mapper.

Use `saveFailed` to retain the failed submission for retry diagnostics and
`saveCancelled` to return to idle without advancing the baseline. A stale token
is reported instead of overwriting current state. A successful save advances
the baseline and replaces the current form only when no newer revision exists;
edits made while saving stay current and remain dirty. `reset` returns
`FormWorkflowReset.Saving(current, submission)` instead of discarding an
in-flight operation, so cancellation policy remains an application decision.

Use `failureForCurrentRevision` when failure feedback should apply only to the
draft that was submitted:

```scala
val currentFailure: Option[SaveError] = model.workflow.failureForCurrentRevision

// An explicit dismiss action preserves the draft and its interaction state.
val dismissed = model.copy(workflow = model.workflow.dismissFailure)
```

The query returns a failure only when the failed submission's revision equals
the current revision. Blur, feedback-only updates, and identical-value updates
retain relevance. Changing editable values hides the feedback but keeps the
failed submission in `save` for diagnostics. Editing away and back does not
revive the failure, because the revision has advanced.

A matching failure completion can be `Applied` after newer edits while
`failureForCurrentRevision` returns `None`: accepting a completion token is not
the same as deciding its feedback applies to the current draft. Applications
can still inspect `save` for failures whose display policy is not draft-specific.

`dismissFailure` clears the stored `Failed` state to `Idle`, preserving the
current form, baseline, revision, and submission-token correlation. It leaves
idle and saving workflows unchanged; it does not cancel persistence or reset
the form. Failure storage is the last retained failed attempt, not an audit log.

The [form save workflow example](../examples/form-save-workflow.md) provides
deterministic controls for invalid and overlapping starts, success, failure,
cancellation, revision-specific failure feedback and dismissal, edits during
save, stale replay, blocked reset, and baseline advancement.

## Reset Deliberately {#reset-deliberately}

For a model that stores `Definition.Form` directly, a reset message should
construct fresh initial form state and clear any saved result. This resets raw
values, used fields, submission state, and visible errors together. Replacing
only input strings can leave stale validation state behind.

For a model that stores `FormWorkflow`, call `workflow.reset` and handle
`FormWorkflowReset.Saving` explicitly. Do not replace an active workflow with a
fresh one: that discards its in-flight correlation state and restarts submission
token generation.

Try change, invalid submit, valid submit, and reset behavior in the
[typed profile form example](../examples/profile-form.md).

## Continue Through Input {#continue-through-input}

This guide is the typed-form step in the interface and input path:

1. [HTML and event bindings](html-dsl-and-event-bindings.md) for controls and browser events.
2. Typed forms and validation for Live-owned structured input.
3. [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) for browser POST and Post/Redirect/Get.
4. [Testing](testing.md#test-typed-form-behavior) for validation visibility, submission, and stable rows.

## Related Tasks {#related-tasks}

- Use [Ordinary HTTP forms and redirects](http-forms-and-redirects.md) when the browser should perform a normal GET or POST.
- Use [Guard unsaved changes](navigation-guards.md#prerequisites) when leaving a dirty form should require confirmation.
- Use [File uploads](uploads-and-consumption.md) for file inputs and resource ownership.
- Use [Authentication and sessions](authentication.md) for credential forms and protected routes.
- Use [Testing](testing.md#test-typed-form-behavior) to exercise changed-field visibility, invalid submission, stable rows, and reset behavior; keep automatic recovery assertions at the [browser boundary](testing.md#test-in-a-browser).
