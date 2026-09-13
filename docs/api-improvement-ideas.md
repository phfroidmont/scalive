# Scalive API Improvement Ideas

Status: evidence-backed proposals, not committed API designs.

This document records the API improvement assessment of OsteoView's `backend.webapp-scalive`
module on 2026-09-13. It replaces the previous backlog rather than carrying its items forward.
Its scope is this consumer assessment; omitting an old item does not imply it was implemented.

The consumer depends on `dev.scalive::scalive:0.0.1-7a865bd757a8-SNAPSHOT`. The reviewed Scalive
checkout was at `7a865bd757a842e1a92af3b8ed11c07186e28ffc`, matching that dependency revision.
These are therefore not gaps that upgrading the consumer to the reviewed checkout would resolve.

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

**Proposed direction.** Start with small pure operations:

```scala
workflow.isSaving
workflow.failureForCurrentRevision
workflow.dismissFailure
```

Failure dismissal should not require restoring the baseline or introducing artificial value
revisions. Distinguish stored failure history from whether a failure is relevant to the current
draft. Do not automatically dismiss failures on every blur or identical-value update.

Then prototype a thin typed save-completion adapter that carries the submission token with the
async result and applies success, failure, or cancellation to the workflow. Keep `Applied` and
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

**Existing support.** [Signal](../scalive/api/src/scalive/Signal.scala) exposes `map` and binary
`zip`. The nested tuples are an ergonomics problem, not evidence of an inefficient or broken
render graph.

**Proposed direction.** Add flat composition with existing map/zip semantics:

```scala
Signal.mapN(
  control.id,
  control.name,
  control.hasVisibleErrors,
  control.errorId,
  selected,
  ready
) { (id, name, invalid, errorId, selected, ready) =>
  // Build component props.
}
```

**Boundaries and verification.** Choose between a small overload family and tuple-based composition
based on readable call sites and compiler errors. Preserve scope validation, dependency tracking,
and unchanged-value behavior. Test updates to each dependency and invalid scope combinations.
There is no demonstrated need here for signal mutation, reactive effects, or `flatMap`.

### 4. Common Form Codecs And Native Control Bindings

**Evidence.** `src/settings/appointmenttypes/AppointmentTypeForm.scala:41-72` manually decodes
checkbox values as absent/false or a single `"true"`/true value, then manually performs the inverse
conversion during initialization. Equivalent conventions appear in automatic-reminder and
account-security forms. Integer parsing and `Option`-returning domain refinements also recur.

`src/patient/PersonalInformationPage.scala:1573-1603` manually assembles native inputs, including
telephone-specific attributes; the birthday field uses this helper with `inputType = "date"` at
line 667. `src/patient/PatientsPage.scala:753-782` manually constructs checkboxes for a signal-backed
label collection.

**Existing support.** [FieldInput](../scalive/api/src/scalive/forms/FieldInput.scala) supports custom
bidirectional codecs plus text, optional text, and repeated text. [FormField](../scalive/api/src/scalive/forms/FormField.scala)
already supports semantic refinement. [FormControl](../scalive/api/src/scalive/forms/FormBinding.scala)
already supplies text, email, password, hidden, checkbox, textarea, and select bindings.

**Proposed direction.** Add a strict `FieldInput.checkbox(...)`, optionally exposed as a root field
constructor, so the accepted wire shape and encoding convention are defined together. Complete
small native bindings such as `control.tel`, `control.date`, and `control.search` using the existing
identity, value, and blur safeguards.

Consider small helpers for `Option`-returning refinements before introducing a larger validation
DSL. For repeated choices, investigate an item binding that provides a shared field name but a
unique item ID and label target. A signal-valued checkbox overload alone does not solve identity.

**Boundaries and verification.** Explicitly reject malformed and duplicate checkbox values. Test
unchecked submission, initial values, feedback wiring, binding-owned attribute rejection, and
unique repeated-choice IDs. Preserve invalid intermediate numeric/date strings. Do not infer
domain parsing from an HTML input type, globally trim text, or silently preserve disabled controls;
these are separate application policies.

### 5. Public Typed Layout-Context Projection

**Evidence.** `src/RootLayout.scala:8-17,44-54` uses `LiveRootLayout[Any, Any]` and recursively
searches nested tuples for language-bearing application contexts:

```scala
case (left, right) => contextLanguage(right).orElse(contextLanguage(left))
```

This couples the shared document shell to the accumulated route-context representation.

**Existing support.** [LiveLayouts](../scalive/api/src/scalive/routing/LiveLayouts.scala) already
implements `contramapContext` for ordinary and root layouts, but keeps both helpers private.
Typed wrappers installed at session or route boundaries can solve this today; the missing piece
is a public composition operation. OsteoView currently installs its shell at the router
(`src/OsteoViewApplication.scala:127`), whose root-layout API accepts `LiveRootLayout[Any, Any]`.

**Proposed direction.** Expose typed context contramapping so the shared shell can consume a small
document context, such as language, with explicit projections attached at the appropriate session
or route boundaries. OsteoView must move the root installation to those boundaries; exposing
contramapping alone does not make the router-level installation typed. Avoid runtime tuple search
or a new context lookup mechanism.

**Boundaries and verification.** Root key and render must use the same projected context. Preserve
root-layout compatibility semantics and test public/private route composition. Projection does
not make document-root attributes reactive during ordinary connected patches; language changes
that require a different document shell must still respect root-key/navigation behavior.

### 6. Effectful HTTP Form Validation Responses

**Evidence.** `src/login/LoginHttpRoutes.scala:44-52` and
`src/settings/accountsecurity/AccountSecurityHttpRoutes.scala:44-52` manually distinguish decoder
validation failures from other rejection categories because validation responses need signed
flash redirects.

**Existing support.** [HttpFormDecoder.respond](../scalive/transport/zio-http/src/scalive/HttpFormDecoder.scala)
accepts `FormErrors[?] => Response`, while [HttpSecurity](../scalive/transport/zio-http/src/scalive/HttpSecurity.scala)
returns `UIO[Response]` for flash redirects. This is a direct composition mismatch.

**Proposed direction.** Add an effectful `respondZIO`, or revise `respond` coherently, so the
validation callback can return an effect. Preserve the rejection observer and centralized
transport/security response mapping.

**Boundaries and verification.** Only semantic validation responses need application-defined
effects. Do not make CSRF, malformed representation, or oversized-body responses freely
overridable. Test callback execution, observer ordering, effect failures, and unchanged rejection
statuses. Returning submitted form state on validation failure is already supported by the
definition-backed `urlEncoded` constructor; that is not a separate missing feature.

### 7. Condition-Based Waiting And Connected Snapshot Queries

**Evidence.** `test/settings/labels/LabelsPageFixture.scala:52-61` implements a loop that reads
`view.html`, tests a predicate, otherwise awaits a diff and repeats, with one overall deadline
applied by a separate helper.
The same algorithm appears in online-booking fixtures, searchable-choice tests, and patient-page
tests. Patient form/page tests also duplicate jsoup helpers to inspect values in retained HTML
snapshots.

**Existing support.** [ConnectedView](../scalive/testing/src/scalive/testing/ConnectedRender.scala)
provides `html`, `text`, and `awaitDiff`, but not condition-based waiting.
[DisconnectedRender](../scalive/testing/src/scalive/testing/DisconnectedRender.scala) already has
richer semantic field queries.

**Proposed direction.** Add a bounded operation such as:

```scala
view.awaitHtml("save confirmation", timeout)(predicate)
```

Check the current snapshot first, enforce one overall deadline, and report the description and
last observed HTML on timeout. Preserve the existing application helpers' overall-deadline behavior
rather than restarting the timeout after every diff.

Separately, reuse existing query semantics in a small parsed HTML snapshot facility with
exactly-one selection and attribute/value access. Supporting retained snapshots matters; a query
of only the latest view would not remove the demonstrated comparison helpers.

**Boundaries and verification.** Test immediate success, multiple intermediate diffs, no matching
update, timeout diagnostics, and snapshot immutability. These are semantic server projections,
not browser DOMs. Do not implicitly execute JavaScript or simulate successful-control selection.
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
