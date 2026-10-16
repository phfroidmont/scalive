{%
title = "Repeated form rows"
description = "Let visitors add, remove, and reorder groups of fields, such as phone numbers, while each row keeps its values and errors."
order = 14
section = guides
group = "Interfaces and input"
%}

## Before you start {#prerequisites}

This guide assumes the form from
[Typed forms and validation](typed-forms-and-validation.md#define-a-rooted-form).
Use repeated rows when each entry has several fields, such as a label and a
number, and the visitor can add, remove, or reorder entries. For a single field
submitted several times, use `Root.texts` and
[`appended` / `removedAt`](typed-forms-and-validation.md#update-fields-programmatically)
instead.

## Model repeated rows {#model-repeated-rows}

Declare a row group under the root, define its fields relative to the group,
and combine them into a row type. The group's product then becomes one field of
the form:

@:sourceRegion(documentation/fixtures/guides/src/forms/ContactForm.scala, forms-rows-definition)

`Contact.Definition` decodes a `Vector[Phone]` in row order, and each row
accumulates its own field errors at that row's address.

## Render the rows {#render-the-rows}

`rows(Contact.PhoneRows)` on the form signal returns the rows with their stable
keys. Render them with `splitBy` on the row key. Each row must render its
presence control, then its fields:

@:sourceRegion(documentation/fixtures/guides/src/forms/ContactForm.scala, forms-rows-render)

`splitBy` passes each row's key and a `Signal` of its row view. The key selects
a row field's control with `contactForm.field(Contact.PhoneRows, key, field)`,
and `contactForm.presence(Contact.PhoneRows, key)` renders the hidden control
that keeps the row in the next submission, even when all its visible fields are
empty or unchecked. With a [form binding](form-feedback-and-recovery.md), use
`binding.field(rows, key, field)` and `binding.presence(rows, key)` instead.

## Give rows stable keys {#give-rows-stable-keys}

Every row has a `FormRowKey` that you choose. Keys, rather than display
indexes, preserve a row's identity, used fields, and error addresses when rows
move. The contacts example builds keys from a counter:

@:sourceRegion(documentation/site/src/scalive/docs/examples/RepeatedContactsFormExample.scala, repeated-contacts-keys)

Start a form with existing rows through the group's `initial` and `row`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/RepeatedContactsFormExample.scala, repeated-contacts-initial)

## Add, remove, and move rows {#add-remove-and-move-rows}

Row operations are ordinary messages. Each row's buttons send its own key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/RepeatedContactsFormExample.scala, repeated-contacts-row-actions)

The handler applies `added` and `removed` to the form in the model:

@:sourceRegion(documentation/site/src/scalive/docs/examples/RepeatedContactsFormExample.scala, repeated-contacts-add-remove)

`movedBefore` and `movedAfter` reorder a row relative to another key:

@:sourceRegion(documentation/site/src/scalive/docs/examples/RepeatedContactsFormExample.scala, repeated-contacts-move-up)

Every operation decodes the form again and keeps each row's interaction state.
Bound the number of rows a visitor can add, as the example does with
`MaximumContacts`. Use `row.bind(field)` with typed `updated`, or explicit
`updatedRaw`, to change one field in one exact row.

The [stable repeated contact rows example](../examples/repeated-contacts-form.md)
shows initialization, rendering, typed events, add, remove, both move
directions, row-local validation, submission, and reset. Its order summary makes
the persistent keys visible while rows move.

## Accept Phoenix nested parameters {#accept-phoenix-nested-parameters}

The core wire shape is keyed and includes the presence control. To accept
Phoenix's indexed nested-parameter convention, adapt that boundary explicitly.
For this recipe the per-row mapping is `contact[phones][0][_persistent_id]`,
while the sibling array controls are `contact[phones_sort][]` and
`contact[phones_drop][]`:

@:sourceRegion(documentation/site/src/scalive/docs/examples/FormRecipes.scala, form-phoenix-repeated-controls)

The bounded `PhoenixNestedParamsAdapter` translates the compatibility payload
into the same keyed form projection. `Adapter.field(formId, row, index, field)`
returns the row field's
@:apiSymbol(class:scalive.FormControl)`FormControl`@:@ under the positional name
and id, such as `contact[phones][0][number]`, while its value and errors come
from the row's stable key. `persistentId(row, index)` reconnects each positional
index to that key, `sortName` supplies order and requests `"new"` rows, and
`dropName` removes indexes. Keep an empty `dropName` control so removing the
last row still submits the group. `configured` accepts
deterministic key allocation and blank-row policy when its defaults do not
match the application. Prefer the core keyed controls for new Scalive
interfaces; use the adapter when interoperating with Phoenix-style clients or
markup.

## Related tasks {#related-tasks}

- Bind blur feedback to a row's control with [Form feedback and recovery](form-feedback-and-recovery.md#normalize-a-value-on-blur).
- Test stable rows with [Testing](testing.md#test-typed-form-behavior).
- Render keyed lists outside forms with [Rendering, bindings, and diffs](../learn/rendering-and-dom-updates.md#preserve-collection-identity).
