# Scalive API Improvement Ideas

Status: evidence-backed proposals with implementation progress noted per item.
Unimplemented directions are not committed API designs.

This document records the API improvement assessment of OsteoView's `backend.webapp-scalive`
module on 2026-09-13. It replaces the previous backlog rather than carrying its items forward.
Its scope is this consumer assessment; omitting an old item does not imply it was implemented.

The consumer depends on `dev.scalive::scalive:0.0.1-7a865bd757a8-SNAPSHOT`. The reviewed Scalive
checkout was at `7a865bd757a842e1a92af3b8ed11c07186e28ffc`, matching that dependency revision.
These were therefore not gaps that upgrading the consumer to the reviewed checkout would resolve.
Implementation notes below describe changes after that assessment.

The strongest opportunities are small improvements to API composition. The application already
uses typed forms, managed async work, components, authorization, and connected testing. Much of
the remaining framework-sized work connects those facilities rather than replaces them.

## Reading This Document

The assessment covered pages, forms, components, routing/session infrastructure, browser hooks,
and representative tests. It was a read-only source assessment: no application behavior was
reproduced, and no builds or tests were run. Correctness-sensitive scenarios below are investigation
targets, not confirmed runtime defects.

OsteoView references use these roots, relative to its repository:

- `src/`: `backend/webapp-scalive/src/solutions/froidmont/osteoview/`
- `test/`: `backend/webapp-scalive/test/src/solutions/froidmont/osteoview/`
- `assets/`: `backend/webapp-scalive/assets/`

The reviewed consumer checkout was `/home/phfroidmont/Projects/froidmont.solutions/osteoview`.
Line references describe the reviewed source and may move. Scalive links are relative to this
repository. All proposed names and signatures are illustrative, not existing API promises.
Recheck current capabilities before adopting an idea, and update its status when it is implemented.

## Design Boundaries

- Prefer small, proven composition points over new controllers, page superclasses, or frameworks.
- Preserve typed ownership, explicit lifecycle boundaries, and stale-result handling.
- Keep domain validation, authorization, persistence, record reconciliation, and UX policy local
  to the application.
- Distinguish missing capabilities from existing facilities that need a complete example.
- Add convenience APIs only when they remove recurring mechanics without hiding important decisions.
- Scalive is alpha: prefer a coherent API over compatibility wrappers for unshipped designs.

## Highest-Value Candidates

### 1. Form Workflow Ergonomics And Save Composition

**Status.** Partially implemented: `isSaving`, `failureForCurrentRevision`, and `dismissFailure`
are available with focused API tests and an updated
[executable workflow example](../documentation/site/src/scalive/docs/examples/FormWorkflowExample.scala).
Save-completion integration and the `Definition.Update` convenience remain proposals; no consumer
migration is included.

**Evidence.** Fourteen settings pages call `beginSave`. They repeatedly connect submission tokens,
async completions, canonical saved values, failure/cancellation, and `Applied` versus `Stale`.
`src/settings/insurances/InsurancesPage.scala:444-488` is a representative completion handler.

Seven settings pages compare the failed submission's revision with the current revision to decide
whether failure feedback still applies. Six others clear failure by resetting the workflow and
reinstalling the incoming form. See `src/settings/onlinebooking/OnlineBookingPage.scala:305-337`.
Several pages also define their own `isSaving` query.

**Existing support.** [FormWorkflow](../scalive/api/src/scalive/forms/FormWorkflow.scala) already
provides dirty tracking, one in-flight save, token correlation, canonical acknowledgement, and
preservation of edits made during saving. [Async](../scalive/api/src/scalive/lifecycle/Capabilities.scala)
already provides managed execution. The gap is composition and explicit feedback policy, not a
missing save state machine.

**Implemented pure operations.**

```scala
workflow.isSaving
workflow.failureForCurrentRevision
workflow.dismissFailure
```

`isSaving` reports the in-flight save state. `failureForCurrentRevision` returns the stored failure
only when the failed submission revision matches the current revision. Value changes hide that
feedback without clearing the stored failed attempt; blur and identical-value updates retain it.
Editing away and back does not revive it. `dismissFailure` clears `Failed` to `Idle` without changing
the form, baseline, revision, or token correlation, and leaves idle/saving workflows unchanged.
See the [workflow guide](../documentation/content/guides/typed-forms-and-validation.md#coordinate-form-workflow)
for the feedback policy and the distinction between accepted completions and relevant failures.

**Remaining direction.** Prototype a thin typed save-completion adapter that carries the submission
token with the async result and applies success, failure, or cancellation to the workflow. Keep `Applied` and
`Stale` explicit so only accepted completions can trigger success notifications, navigation, or
record reconciliation. Keep the workflow itself pure; effectful conveniences belong at its
lifecycle integration boundary.

A smaller adjacent convenience is applying a `Definition.Update` directly to a workflow's current
form. Today, `src/settings/labels/LabelsPage.scala:60-68` applies the update and then installs
`applied.form` into the workflow. Any convenience must preserve access to interaction metadata
such as `valuesChanged` when the caller needs it.

**Boundaries and verification.** Preserve duplicate-submit rejection, workflow-instance token
isolation, stale success/failure/cancellation handling, and edits made during a save. Test failure
visibility after value changes versus feedback-only updates. Canonicalization failures must remain
explicit. Try the adapter on one simple settings page and one editable-during-save dialog before
generalizing it. Create/update decisions, server IDs, list refreshes, and dialog closing remain
application code.

### 2. Connected Resources At Route And Session Boundaries

**Evidence.** `src/organization/OrganizationRoute.scala:92-138` defines two forwarding LiveView
wrappers, one ordinary and one routed. Their substantive change is acquiring a session-expiry
lease before delegating mount; the rest forwards lifecycle methods. The acquisition itself already
uses `ConnectedResources.acquireRelease` through `src/session/SessionMount.scala:17-22`.

**Existing support.** [ConnectedResources](../scalive/api/src/scalive/lifecycle/Capabilities.scala)
provides lifecycle-owned cleanup. [Routing](../scalive/api/src/scalive/routing/LiveRouting.scala)
provides mount aspects, admission, guards, and factories, but not a declarative attachment point
for this connected resource initialization.

**Proposed direction.** Expose a route/session modifier along these lines:

```scala
.withConnectedResources { (context, resources) =>
  resources.acquireRelease(acquireLease(context))(_.release).unit
}
```

Run it after admission and typed context construction, before the page's connected mount. The
same facility should work for ordinary and routed views without application forwarding wrappers.

**Boundaries and verification.** Ownership remains the connected LiveView lifecycle, not the
physical socket or logical application session. Specify ordering when multiple initializers are
composed. Test disconnected rendering, rejected admission, acquisition or mount failure, connected
navigation, disconnect, and exactly-once release. Expiry policy and cross-tab sharing through a
reference-counted service remain application-owned.

### 3. Flat Multi-Signal Composition

**Status.** Implemented as `Signal.combine`, `Signal.combineWithFn`, and binary instance
`combineWith` / `combineWithFn`. Public `Signal.zip` was removed in this breaking alpha API change.
Repository callers were migrated; consumer migration remains application work. See
[signal composition](../documentation/content/learn/rendering-and-dom-updates.md#derive-display-values).

**Evidence.** `src/patient/PersonalInformationPage.scala:1842-1877` contains the following assembly
twice, for single- and multi-choice props:

```scala
control.id
  .zip(control.name)
  .zip(control.hasVisibleErrors)
  .zip(control.errorId)
  .zip(selected)
  .zip(ready)
  .map { case (((((id, name), invalid), errorId), current), state) =>
    // Build component props.
  }
```

Related multi-input combinations occur in `src/components/SearchableChoiceFieldView.scala`.

**Support at assessment.** [Signal](../scalive/api/src/scalive/Signal.scala) exposed `map` and binary
`zip`. The nested tuples were an ergonomics problem, not evidence of an inefficient or broken
render graph. The historical consumer snippet above predates the removal of `zip`.

**Implemented composition.** The companion operations accept a nonempty heterogeneous tuple,
without a fixed-arity overload family:

```scala
Signal.combineWithFn((
  control.id,
  control.name,
  control.hasVisibleErrors,
  control.errorId,
  selected,
  ready
)) { (id, name, invalid, errorId, selected, ready) =>
  // Build component props.
}
```

`Signal.combine` preserves each signal value as one result element. Binary `a.combineWith(b)`
shallowly concatenates statically tuple-shaped operands, so chaining builds flat tuples while
nested tuple elements and case-class values remain intact. Both `combineWithFn` forms pass one
original value per input signal; they do not flatten tuple-valued function arguments.

**Boundaries and verification.** The implementation retains the existing render expressions,
scope validation, dependency tracking, and unchanged-value behavior. Focused tests cover more
than 22 inputs, singleton and invalid input tuples, tuple grouping, generic/widened types,
independent updates, shared dependencies, evaluation order, and incompatible scopes.
There is no new signal mutation, reactive effect, or `flatMap` API.

### 4. Common Form Codecs And Native Control Bindings

**Status.** Partially implemented: `FieldInput.checkbox` provides a strict Boolean codec,
with API, workflow, and binding tests plus a
[compiled recipe](../documentation/site/src/scalive/docs/examples/FormRecipes.scala).
Native `tel`, `date`, and `search` helpers are available on bound controls, field views,
and signal-backed field views. Refinement conveniences and repeated-choice identity
remain proposals. No consumer migration is included.

**Evidence.** `src/settings/appointmenttypes/AppointmentTypeForm.scala:41-72` manually decodes
checkbox values as absent/false or a single `"true"`/true value, then manually performs the inverse
conversion during initialization. Equivalent conventions appear in automatic-reminder and
account-security forms. Integer parsing and `Option`-returning domain refinements also recur.

`src/patient/PersonalInformationPage.scala:1573-1603` manually assembles native inputs, including
telephone-specific attributes; the birthday field uses this helper with `inputType = "date"` at
line 667. `src/patient/PatientsPage.scala:753-782` manually constructs checkboxes for a signal-backed
label collection.

**Support at assessment.** [FieldInput](../scalive/api/src/scalive/forms/FieldInput.scala) supported custom
bidirectional codecs plus text, optional text, and repeated text. [FormField](../scalive/api/src/scalive/forms/FormField.scala)
already supports semantic refinement. [FormControl](../scalive/api/src/scalive/forms/FormBinding.scala)
already supplies text, email, password, hidden, checkbox, textarea, and select bindings.

**Implemented codec.** `Root.field("enabled", FieldInput.checkbox())` decodes absence as false,
one exact checked token (default `"true"`) as true, and rejects other singletons or any duplicates.
Encoding false omits the value; encoding true emits the configured token. Custom tokens and issues
are supported; the renderer must use the same token. Root and repeated-row fields use the existing
`field` constructor. See the [control guide](../documentation/content/guides/typed-forms-and-validation.md#render-richer-controls).

**Implemented native helpers.** `control.tel`, `control.date`, and `control.search` reuse the
existing scoped identity, raw value, blur wiring, and binding-owned attribute safeguards.
The corresponding plain and signal-backed field-view helpers preserve their existing logical
identity and modifier behavior. ARIA remains opt-in; no parsing or validation is inferred from
the input type. The [blur example](../documentation/site/src/scalive/docs/examples/BlurFeedbackExample.scala)
now uses the telephone helper instead of manual input assembly.

**Remaining direction.** Consider small helpers for `Option`-returning refinements before introducing
a larger validation DSL. For repeated choices, investigate an item binding that provides a shared field name but a
unique item ID and label target. A signal-valued checkbox overload alone does not solve identity.

**Boundaries and verification.** Codec tests cover malformed and duplicate values, custom and empty
tokens, initial values, unchecked submission, repeated-row presence, unchanged-value/revision
behavior, and binding feedback. Native-helper tests cover public typing, retained raw values,
reactive updates, identity, attribute ownership, and blur behavior. Browser tests distinguish
date attributes and retained server values from the native sanitized value and subsequent form
snapshots. Raw strings remain in server state until replaced, but native controls may not display
malformed intermediate values; use text controls when that is required. Repeated choices still
need unique item IDs. Do not infer domain parsing from an HTML input type, globally trim text, or
silently preserve disabled controls; these are separate application policies.

### 5. Public Typed Layout-Context Projection

**Status.** Implemented as `layout.forContext[NewContext](select)` on ordinary and root layouts,
with `withLayout(layout, select)` / `withRootLayout(root, select)` conveniences on typed session,
admitted-session, and mounted-route builders. The private companion helpers were removed and
internal callers migrated. See the
[shared-shell example](../documentation/content/guides/layouts-sessions-and-mount-aspects.md#reuse-layouts-across-contexts).
Consumer migration remains application work.

**Evidence.** `src/RootLayout.scala:8-17,44-54` uses `LiveRootLayout[Any, Any]` and recursively
searches nested tuples for language-bearing application contexts:

```scala
case (left, right) => contextLanguage(right).orElse(contextLanguage(left))
```

This couples the shared document shell to the accumulated route-context representation.

**Support at assessment.** [LiveLayouts](../scalive/api/src/scalive/routing/LiveLayouts.scala) already
implemented private `contramapContext` helpers for ordinary and root layouts. Typed wrappers
installed at session or route boundaries could solve the problem, but public composition was
missing. The reviewed OsteoView checkout installed its shell at the router
(`src/OsteoViewApplication.scala:127`), whose root-layout API accepts `LiveRootLayout[Any, Any]`.

**Implemented composition.** `forContext` adapts an existing layout by selecting the context it
needs. Installation selectors provide the same behavior without a separately named adapter.
Later aspects preserve the context available at installation; neither operation changes the
route's context or introduces a layout factory. Router-level installation remains context-free,
so OsteoView must move context-dependent roots to typed session or route boundaries. There is
no runtime tuple search or new context lookup mechanism.

**Boundaries and verification.** Root key and render apply the same pure, deterministic selector
independently. Tests cover public typing and inference, unchanged signal and request inputs,
layout precedence, later aspects, and shared document contexts with signed root keys. Projection
does not make document-root attributes reactive during ordinary connected patches; language
changes still respect root-key/navigation behavior and named-session boundaries.

### 6. Effectful HTTP Form Validation Responses

**Status.** Implemented by changing `respond`'s validation callback to
`FormErrors[?] => ZIO[R, E, Response]`, without adding a parallel `respondZIO` operation.
This is a breaking alpha API change; plain validation responses now use `ZIO.succeed`.
The [authentication lab](../documentation/site/src/scalive/docs/auth/AuthLab.scala) now uses
`respond` for signed validation-flash redirects instead of manually dispatching decoder errors.
Consumer migration remains application work.

**Evidence.** `src/login/LoginHttpRoutes.scala:44-52` and
`src/settings/accountsecurity/AccountSecurityHttpRoutes.scala:44-52` manually distinguish decoder
validation failures from other rejection categories because validation responses need signed
flash redirects.

**Support at assessment.** [HttpFormDecoder.respond](../scalive/transport/zio-http/src/scalive/HttpFormDecoder.scala)
accepted `FormErrors[?] => Response`, while [HttpSecurity](../scalive/transport/zio-http/src/scalive/HttpSecurity.scala)
returned `UIO[Response]` for flash redirects. This was a direct composition mismatch.

**Implemented composition.** Both response callbacks can require an environment and fail through
the returned effect's error channel. The rejection observer runs before rejection response
handling, and centralized transport/security mapping remains unchanged. Application callback
failures are not decoder rejections and do not invoke the observer again.

**Boundaries and verification.** Only semantic validation rejections have application-defined
response effects. CSRF, malformed representation, and oversized-body responses are not freely
overridable. Focused tests cover deferred callback execution, observer ordering, effect failures,
unchanged rejection statuses, and the auth lab's signed validation-flash round trip.
Returning submitted form state on validation failure was already supported by the
definition-backed `urlEncoded` constructor; that is not a separate missing feature.

### 7. Condition-Based Waiting And Connected Snapshot Queries

**Status.** Partially implemented: bounded `ConnectedView.awaitHtml` waiting is available with
focused harness tests and a migrated private helper in the
[async-report example spec](../documentation/site/test/src/scalive/docs/examples/AsyncReportExampleSpec.scala).
Immutable parsed queries over retained HTML snapshots remain a proposal. No consumer migration
is included.

**Evidence.** `test/settings/labels/LabelsPageFixture.scala:52-61` implements a loop that reads
`view.html`, tests a predicate, otherwise awaits a diff and repeats, with one overall deadline
applied by a separate helper.
The same algorithm appears in online-booking fixtures, searchable-choice tests, and patient-page
tests. Patient form/page tests also duplicate jsoup helpers to inspect values in retained HTML
snapshots.

**Existing support.** [ConnectedView](../scalive/testing/src/scalive/testing/ConnectedRender.scala)
provides `html`, `text`, `awaitDiff`, and now condition-based `awaitHtml`.
[DisconnectedRender](../scalive/testing/src/scalive/testing/DisconnectedRender.scala) already has
richer semantic field queries.

**Implemented waiting.**

```scala
view.awaitHtml("save confirmation", timeout)(predicate)
```

The operation checks current HTML first, then rechecks after uncorrelated async diffs within one
overall deadline. It returns the exact matching HTML; timeout diagnostics include the description,
requested duration, and last observed HTML. The deadline does not restart after each diff or inherit
`awaitDiff`'s five-second limit. See the
[testing guide](../documentation/content/guides/testing.md#wait-for-an-html-condition).

**Remaining direction.** Reuse existing query semantics in a small immutable parsed HTML snapshot facility with
exactly-one selection and attribute/value access. Supporting retained snapshots matters; a query
of only the latest view would not remove the demonstrated comparison helpers.

**Boundaries and verification.** Waiting tests cover current matches, intermediate diffs, bounded
timeouts, diagnostics, predicate failures, and view retirement/disconnection. Predicates must be
quick and nonblocking. Waiting consumes the same queue as `awaitDiff`: use a single waiter per view
and finish correlated actions first. It never follows navigation. Checks observe the latest semantic
server projection and can miss transient states; they are not browser DOMs. Test retained-snapshot
immutability when adding parsed queries. Do not execute JavaScript or simulate successful-control selection.
Application service fakes and authorization fixtures should remain in the application.

## Investigations

### 8. Complete Composite-Control Integration

**Evidence.** `src/patient/PersonalInformationPage.scala:70-117,570-583` composes eleven choice
components and an auxiliary search form. `src/components/SearchableChoiceFieldView.scala:205-260`
separates hidden selected values from the widget's search input. Parent integration supplies field
identity, validation metadata, selected records, presentation, and source revision. Custom choices
also use a separate feedback binding from the main form.

**Existing support.** Typed component outputs, custom controls, `control.view`, validation
attributes, and `control.blurred` already exist. The [typed forms guide](../documentation/content/guides/typed-forms-and-validation.md)
describes logical whole-widget blur. This is not evidence that Scalive lacks custom inputs or
component communication.

**Proposed direction.** First build a maintained, executable controlled-input example covering
parent-owned selection, widget-local search, hidden single/repeated values, whole-widget blur,
component targeting, keyed-row removal, disabled behavior, and recovery. Use it to determine
whether field metadata packaging or explicit per-control feedback configuration is warranted.
Do not assume changing feedback policy per control is already supported by a trivial adapter.

**Boundaries and verification.** Verify the example in a browser, especially search isolation,
keyboard focus, blur across internal elements, recovery, and removal of a focused row. Keep one
owner of the selected form value. Search services, labels, pagination, keyboard interaction, popup
positioning, and styling remain legitimate widget/application responsibilities. Do not bundle a
combobox solely because its hook is substantial.

### 9. Revision-Aware Normalization And Editor Reconciliation

**Evidence.** `src/patient/PersonalInformationPage.scala:705-721,2102-2108` normalizes phone values
on blur. `assets/hooks/rich-text-editor.js:148-223` keeps a bounded history of locally emitted
values to distinguish echoed edits from incoming replacements.

**Existing support.** Typed form updates, save-submission tokens, and browser input patching exist,
but they do not provide this reconciliation guarantee. The [typed forms guide](../documentation/content/guides/typed-forms-and-validation.md)
explicitly states that blur command ordering does not await acknowledgements and that a later
full-form snapshot can overwrite a server-normalized value before its patch reaches the browser.

**Proposed direction.** Investigate an opt-in normalization and acknowledgement contract tied to
the identity/revision of the browser edit being normalized. It must distinguish echoes from
explicit server resets, reject outdated normalization responses, and address older full-form
snapshots arriving after normalization. A custom-editor integration should use an explicit
contract rather than require value-history heuristics.

**Boundaries and verification.** Reproduce the relevant ordering scenarios before designing or
implementing an API. Test delayed patches, rapid subsequent edits, consecutive blur/change events,
resets to previously emitted values, and reconnect/recovery. Account for browser-client integration
cost and upstream compatibility. Merely wrapping `.onBlur(...updated...)` in `normalizeOnBlur`
would overpromise. Normalization rules and rich-text conflict/merge policy remain application-owned.

## Lower-Priority Candidates

### 10. Mount-Aware Flash Consumption

**Evidence.** `src/login/LoginPage.scala:25-35`, `src/login/RegisterPage.scala:20-42`, and
`src/login/ResetPasswordPage.scala:25-45` repeat reading flash in either mount phase but clearing
it only during connected mount. Together these cover six keys.

**Existing support.** [Flash](../scalive/api/src/scalive/lifecycle/Capabilities.scala) provides
`get`, `clear`, `clearAll`, and `snapshot`. The repeated part is the phase-specific consumption rule.

**Proposed direction.** Consider a narrowly named mount-context operation that reads in either
phase and clears on connected mount. Avoid a general `Flash.take` whose behavior silently changes
with context.

**Boundaries and verification.** Test disconnected bootstrap, connected mount, and reconnect
behavior. Do not promise exactly-once delivery across reloads, retries, or tabs. Clearing flash
does not clear a value already copied into page model state. This does not justify flash-backed
draft persistence.

### 11. Checked Local Return Destinations

**Evidence.** `src/login/ReturnDestination.scala:16-50` combines security-sensitive local URL
validation, query parsing, encoding, and redirect conversion. `src/login/ResetPasswordTarget.scala`
independently handles query encoding/decoding with its own duplicate-value policy.

**Existing support.** [LiveLocation](../scalive/api/src/scalive/LiveLocation.scala) models
route-produced locations, and [LiveParamsCodec](../scalive/api/src/scalive/LiveParamsCodec.scala)
already supports typed queries. These cover much of the query plumbing, but arbitrary inbound
local destinations are not route-produced values. Unsafe navigation validation is not a local
return-destination allowlist.

**Proposed direction.** Consider a separate checked local-destination type usable with redirects
and flash redirects, without weakening `LiveLocation`. Pair it with a recipe using existing typed
query facilities for login return parameters.

**Boundaries and verification.** Keep the organization's allowed path prefix and fallback landing
page in application code. Define and test authority/scheme handling, encoded separators, path
normalization, control characters, queries, and fragments. Preserve intentional malformed-link
UX and duplicate-parameter rejection when adopting typed query decoding. Do not infer
authorization from a destination being local.

### 12. Optional Headless Modal Lifecycle Support

**Evidence.** `assets/hooks/catalog-dialog.js:12-70` and
`assets/hooks/organization-layout.js:38-66,113-140` both manage background inertness, scroll locking,
focus restoration, and cleanup. The application already uses Scalive's `focusWrap`.

**Existing support.** [Focus wrapping](../scalive/api/src/scalive/defs/components/Components.scala)
and the [browser JS commands](../documentation/content/guides/browser-integration.md) provide
building blocks, but not a complete nested modal lifecycle.

**Proposed direction.** Prefer an executable recipe or optional headless browser utility before
adding core API. Own only initial/return focus, background isolation, scroll-lock ownership,
nesting, and cleanup on removal. Evaluate native `dialog` behavior against the supported browser
client and patch lifecycle before selecting an approach.

**Boundaries and verification.** Test nested overlays, patch removal, focus return to removed
elements, reconnect, and preservation of pre-existing inert/scroll state. Closing one overlay
must not unlock another. Styling, responsive drawer behavior, and discard-confirmation decisions
remain application-owned. This is not a proposal for a bundled UI kit.

### 13. Checked Canonical Form Encoding

**Evidence.** Save handlers repeatedly rebuild a form from a persisted entity and request
`validSnapshot`, for example `src/settings/locations/LocationsPage.scala:493-503`. Entity-to-input
mapping is explicit in `src/settings/locations/LocationForm.scala:13-73`.
`src/settings/publicprofile/PublicProfileForm.scala:167-215` also preserves repeated-row keys when
constructing canonical values, showing why saved-record conversion is not always a simple inverse.

**Existing support.** [FormDefinition](../scalive/api/src/scalive/forms/FormDefinition.scala)
supports initialization and reconstruction from owned values, and
[FormWorkflow](../scalive/api/src/scalive/forms/FormWorkflow.scala) already accepts validated
canonical snapshots. Semantic field mappings are intentionally one-way.

**Proposed direction.** Prototype an optional explicit encoding contract that maps a persisted
record to field inputs and returns either a checked canonical snapshot or useful diagnostics.
Use it for initialization and save acknowledgement where that genuinely removes repetition.
First establish whether a small application helper is sufficient.

**Boundaries and verification.** Do not infer the inverse of arbitrary `map`/`emap`, introduce
automatic derivation without evidence, or discard invalid persisted data into an unexplained
`None`. Test invalid canonical data, server-normalized values, preservation of later edits, and
stable repeated-row keys. Row identity matching and domain-specific defaulting remain explicit
application decisions.

## Recommended Sequence

1. Implement bounded composition improvements: flat signal composition, workflow status/failure
   operations, effectful HTTP validation, public layout projection, checkbox codecs, and
   condition-based test waiting. Add focused tests and examples with each change.
2. Add connected-resource composition and verify it by removing the consumer's ordinary/routed
   forwarding wrappers without changing session-expiry policy.
3. Prototype save-completion integration on one simple settings page and one editable-during-save
   dialog. Judge it by reduced plumbing and preserved explicit domain behavior, not line count alone.
4. Build the composite-control example, then investigate browser reconciliation separately with
   delayed-patch, rapid-edit, reset, and reconnect scenarios.
5. Revisit lower-priority candidates only when their prototypes demonstrate a meaningful reduction
   in repeated mechanics. Promote reusable application helpers only after their contracts are clear.

## What Not To Generalize

- Do not add a generic CRUD-page superclass: permissions, archive rules, activation toggles,
  pagination, record reconciliation, and dialog policies differ meaningfully across pages.
- Do not replace the pure save workflow or hide stale completion decisions behind automatic effects.
- Do not absorb the application's identity system, session-expiry policy, or cross-tab resource
  sharing into the framework.
- Do not treat all custom JavaScript or repeated HTML styling as evidence of missing core API.
- Do not expand the connected server-side test harness into a partial browser implementation.
- Do not reintroduce items from the previous backlog without fresh evidence and a current API check.
