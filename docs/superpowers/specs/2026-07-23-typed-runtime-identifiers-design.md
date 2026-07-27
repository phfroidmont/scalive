# Typed Runtime Identifiers Design

## Goal

Replace repeated string identifiers with typed declarations where doing so removes a concrete class of invalid Scala programs. Preserve strings where the value is free-form browser syntax, external input, or a local label for which a wrapper would add ceremony without meaningful safety.

This design addresses durable runtime resource keys and typed event contracts without claiming that identifiers shared with JavaScript are checked end to end.

## Decision Criteria

An identifier should receive a public type when all of the following hold:

1. The value is the stable identity of a logical resource or contract rather than free-form syntax or user data.
2. The identity is reused across operations, lifecycle phases, or layers.
3. A mismatch can target the wrong resource, silently do nothing, replace an unintended resource, or fail later at runtime.
4. The type prevents a plausible mistake, such as mixing identifier families or associating one declaration with incompatible task results or event payloads.
5. Users can naturally declare the identifier once and reuse it without storing a runtime handle in their model.
6. No existing typed value already carries the identity adequately.

An identifier should remain a string when one or more of the following dominate:

- It is free-form browser syntax, such as a CSS selector, DOM ID, HTML attribute name, or unsafe URL.
- It comes from external input or generated data.
- It is a one-shot label with no later lookup or associated contract.
- A wrapper would provide neither validation nor an associated type and would only replace `String` with constructor ceremony.
- The API already distinguishes the namespace structurally and a private string constant provides effectively the same protection.

Cross-language use does not by itself disqualify a typed declaration. A type is worthwhile when it guarantees consistency among Scala call sites, provided the documentation clearly identifies the untyped external boundary.

## Scope

Introduce these public identifier types:

- `UploadKey`
- `AsyncKey[A]`
- `SubscriptionKey`
- `FlashKind`
- `ClientEvent[A]`

`LiveStreamDef[A]` already provides a typed stream identity. `LiveLocation` already provides typed outbound locations.

Keep these concepts as strings:

- lifecycle hook IDs
- client `phx-hook` names
- CSS selectors and DOM IDs
- HTML attribute and value names
- explicitly unsafe URLs and paths

Lifecycle hook IDs remain strings because the hook facade already selects the registry and callback type. Category-specific hook key types would primarily protect uncommon `detach` calls while adding several public concepts. A generic `HookId` would not prevent wrong-registry use.

## Public API

Applications declare keys once near the owning LiveView, component, or shared protocol definition:

```scala
val Avatar    = UploadKey("avatar")
val Refresh   = AsyncKey[User]("refresh-user")
val Clock     = SubscriptionKey("clock")
val Info      = FlashKind("info")
val Refreshed = ClientEvent[UserRefreshed]("user:refreshed")
```

Primary operations accept only their domain type:

```scala
ctx.uploads.allow(Avatar, options)
ctx.uploads.get(Avatar)
ctx.uploads.cancel(Avatar, entryRef)

ctx.async.start(Refresh)(loadUser)(Msg.UserLoaded.apply)
ctx.async.cancel(Refresh)

ctx.subscriptions.start(Clock)(ticks)
ctx.subscriptions.replace(Clock)(newTicks)
ctx.subscriptions.cancel(Clock)

ctx.flash.put(Info, "Saved")
ctx.flash.clear(Info)
flash(Info)(message => div(message))

ctx.client.push(Refreshed, UserRefreshed(user.id))
```

Raw-string overloads and implicit string lifting are not retained. Dynamic applications can construct a typed identifier from a dynamic string. Arbitrary client payloads can use a suitable payload type such as `zio.json.ast.Json`.

No separate public unsafe overload is required for these APIs. The constructors preserve the domain distinction even when their string value is dynamic, and every Scala value supplied to a client event has a statically known payload type.

## Type Guarantees

`AsyncKey[A]` associates a task name with its result type. `Async.start` requires a `Task[A]` for the supplied key, and `cancel` accepts a key regardless of its result type. Restarting a declared key with a different task result type does not compile.

`ClientEvent[A]` associates an event name with its payload type. `Client.push` requires both a matching payload and a `JsonEncoder[A]`. This guarantees consistency across Scala emission sites only. JavaScript still subscribes by string and interprets JSON dynamically.

`UploadKey`, `SubscriptionKey`, and `FlashKind` provide nominal namespace separation. They prevent accidental cross-family use but do not claim to detect two different values from the same family or same-family spelling mistakes. Declaring and reusing one value remains the mechanism for avoiding spelling drift.

## Representation

Each identifier is an invariant opaque type over `String`, colocated with its domain rather than exposed through a generic branded-key abstraction. Each companion provides:

```scala
def apply(value: String): KeyType
extension (key: KeyType) def value: String
```

There are no implicit conversions in either direction. The explicit `.value` accessor is the interoperability boundary for logging, custom JavaScript generation, and integrations that need the wire value.

Construction adds no validation in this change. Existing runtimes remain responsible for domain restrictions, avoiding an accidental behavior change for identifiers Phoenix currently accepts.

Runtime maps and protocol payloads may continue using strings internally. Public facades convert at the boundary. Error messages continue to include the underlying string value.

`ClientEvent[A]` does not retain a JSON encoder. `Client.push` resolves `JsonEncoder[A]` at the call site, keeping event declarations lightweight.

## Application-Facing Propagation

Typed identities propagate through public values and callbacks where exposing a string would immediately discard the guarantee:

- `LiveUpload.name` becomes `UploadKey`.
- Public upload writer and progress callbacks receive `UploadKey`.
- `Flash.snapshot` becomes `Map[FlashKind, String]`.
- The render-time `flash` helper accepts `FlashKind`.
- `LiveAsyncEvent` exposes an erased `AsyncKey[Any]` because Scala 3 opaque types do not support wildcard application and the task result has already been converted to the LiveView message type by completion time.

Internal protocol models may continue carrying strings when they are not application-facing.

## Compatibility

This is intentionally source-breaking. Scalive is alpha, and preserving raw-string overloads would preserve the invalid states this design removes.

The wire format, Phoenix event names, runtime ownership rules, and component scoping do not change. Existing applications migrate by declaring typed values and replacing repeated string arguments with those declarations.

## Error Handling

Key construction is total and does not add new failures. Runtime failures retain their existing behavior and wording apart from implementation changes needed to unwrap typed values. Client payload encoding failures continue to fail with the event's underlying name in the error message.

## Testing

Compile-time API tests will verify:

- positive use of every key family
- rejection of cross-family identifiers
- rejection of a `Task` whose result does not match `AsyncKey[A]`
- rejection of a client payload that does not match `ClientEvent[A]`
- absence of implicit conversions between keys and strings
- construction only through the public companion APIs

Runtime tests will verify unchanged behavior for:

- async task start, replacement, completion, cancellation, and hooks
- upload allow, lookup, cancellation, consumption, writer callbacks, and progress callbacks
- subscription start, replacement, and cancellation
- flash put, get, clear, snapshot, navigation, and rendering
- client event JSON encoding and diff output

Tests should assert public behavior through external API test packages where opacity matters and use package-private access only for protocol-level behavior.

## Documentation

Update the public API reference, examples, and API improvement notes to declare keys once and reuse them. Document the exact Scala-side guarantee of typed client events and the untyped JavaScript boundary.

Narrow the user-facing API assessment finding after implementation: durable resource identifiers and payload contracts are typed, while deliberately free-form browser identifiers remain strings according to the documented criteria.

## Non-Goals

This change does not add:

- lifecycle hook key types
- selector or DOM ID parsers and builders
- identifier validation rules
- generated Scala or TypeScript client contracts
- backward-compatible string overloads
- wire protocol or Phoenix behavior changes
