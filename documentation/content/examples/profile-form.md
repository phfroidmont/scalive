{%
title = "Typed profile form"
description = "Schema-owned fields and a typed submitter rebuild validated profile previews and saves from native form events."
order = 4
section = examples
%}

Change individual fields to reveal only their relevant validation feedback, or
preview or save the form to validate every field. The definition-owned submitter decodes the
selected operation as a typed enum while valid input becomes a `Profile`; reset returns the nested
@:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ to pristine state.

@:example(profile-form)

Related guidance: [build typed forms and validation](../guides/typed-forms-and-validation.md#define-a-rooted-form).
