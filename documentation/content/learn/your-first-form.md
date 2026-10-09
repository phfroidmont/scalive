{%
title = "Your first form"
description = "Build a two-field sign-up form that validates as the visitor types, reports errors, and resets after a successful submission."
order = 4
section = learn
%}

## Define The Form {#define-the-form}

A form starts as a definition of its fields and the value they decode to. This
lesson builds a sign-up form with a name and an email address. Create
`app/src/quickstart/Signup.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Signup.scala, learn-signup-definition)

@:apiSymbol(class:scalive.FormRoot)`FormRoot("signup")`@:@ names the form, and
each field's HTML name is nested under it, such as `signup[name]`. Each field
reads text, trims it, and checks it. `required` rejects an empty value and
`validate` rejects a value that fails its predicate. The `FieldIssue` text is
the message shown to the visitor. `product` combines the fields into
`Signup.Definition`, which decodes a complete `Signup` only when every field is
valid.

The definition is plain data. It does not know about any LiveView, so tests and
other pages can use it too.

## Keep The Form In The Model {#keep-the-form-in-the-model}

The LiveView keeps the form's current state in its model, next to what happened
last:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-model)

`Signup.Definition.Form` holds the raw text the visitor typed, the current
errors, and which fields the visitor has used. Its `Event` is what the browser
sends when the form changes or is submitted. `mount` starts from an empty form:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-mount)

## Validate As The Visitor Types {#validate-as-the-visitor-types}

`Signup.Definition.onChange(Msg.Changed(_))` in the view sends `Msg.Changed`
each time an input changes. The event already contains the updated, validated
form, so the handler only stores it:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-change)

Storing `event.form` is what keeps the inputs and their errors in step with the
browser. Without it, the next render would show the previous form.

## Handle The Submission {#handle-the-submission}

`Signup.Definition.onSubmit(Msg.Submitted(_))` sends `Msg.Submitted` when the
visitor submits. `event.form.result` is `Right(signup)` when every field is
valid, and `Left(issues)` otherwise:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-submit)

On success, the handler records the `Signup` and resets the form by building a
fresh `Signup.Definition.initial()`. On failure, it stores the submitted form.
A submitted form marks every field as used, so all of its errors appear at once.

A real application would save the sign-up through a service before resetting
the form. [Loading data and live updates](loading-data-and-live-updates.md)
shows how a LiveView gets a service.

## Render Fields And Errors {#render-fields-and-errors}

The view reads each field from a signal of the form:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-view)

`signupForm.field(Signup.Name)` gives the field's `id`, `name`, current value,
and errors. `name.text(...)` renders a text input with all of them, and
`email.email(...)` an email input. `validationAttributes` adds `aria-invalid`
and links the input to its error list for screen readers. `errorFeedback`
renders the field's visible errors, one per issue.

An error becomes visible once the visitor has used its field or submitted the
form. This keeps a fresh form from opening covered in "Enter your name."

## Try It {#try-it}

The complete `app/src/quickstart/SignupLiveView.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/SignupLiveView.scala, learn-signup-live-view)

Add its route to `Routes.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Routes.scala, learn-signup-route)

Register it in `Main.scala`:

@:sourceRegion(documentation/fixtures/learn/src/quickstart/Main.scala, learn-signup-registration)

Run `mill app.run` and open `http://localhost:8080/signup`:

1. Type `ada` in **Email**. The email error appears; the name field shows none
   because you have not used it.
2. Select **Sign up**. Both errors appear.
3. Fill in a name and a valid email, then select **Sign up** again. The page
   thanks you and the form is empty again.

The [typed forms guide](../guides/typed-forms-and-validation.md) covers initial
values, other input types, repeated rows, and saving in the background.
Next, [Loading data and live updates](loading-data-and-live-updates.md) gives a
page a service.
