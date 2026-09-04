{%
title = "Typed profile form"
description = "Schema-owned fields rebuild typed change and submit events with canonical values and logical-address validation."
order = 4
section = examples
%}

Change individual fields to reveal only their relevant validation feedback, or
submit the form to validate every field. Valid input is decoded into a typed
`Profile`; reset returns the nested @:apiSymbol(trait:scalive.LiveView)`LiveView`@:@ to pristine state.

@:example(profile-form)

Related guidance: [build typed forms and validation](../guides/typed-forms-and-validation.md#define-a-rooted-form).
