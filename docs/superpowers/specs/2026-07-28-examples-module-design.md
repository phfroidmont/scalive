# Scalive Examples Module Design

## Goal

Replace the development-oriented `example` application with a user-oriented `examples` module that demonstrates the major ways to build a Scalive application.

The module is one runnable showcase. Each route teaches one focused concept while the application shell demonstrates server setup, assets, layouts, typed routing, service construction, and route composition.

## Architecture

Rename the Mill module and directory from `example` to `examples`. Move Scala sources into named packages rooted at `scalive.examples` and organize them by feature area.

`ExamplesApp` owns server startup, shared service construction, ordinary HTTP routes, Live route registration, static assets, and logging. LiveView callbacks use `LiveIO`, which is a `Task`, so application services needed by callbacks are captured through constructor injection. Dependency-backed LiveViews expose constructor-derived layers at route registration, and their shared input service layers are provided at the server boundary. Route authentication uses a ZIO environment through `LiveMountAspect`.

The catalog is data-driven only for presentation. Each entry contains a category, title, description, and typed `LiveLocation`; route registration remains explicit so readers can see the normal Scalive composition style.

## Catalog

The first version contains these focused examples:

| Area | Example | Concepts |
| --- | --- | --- |
| Getting started | Catalog | `ZIOAppDefault`, assets, layouts, eventless LiveView, typed routes |
| State | Shopping cart | typed model/messages, synchronous updates, keyed rendering |
| Services | Guestbook | service trait, route-level LiveView `ZLayer`, inferred constructor injection, shared server state |
| Subscriptions | Clock | `ZStream`, typed subscription key, start, replace, and cancellation |
| Async work | Report generator | start, replace, cancel, retry, success, failure, and `AsyncValue` |
| Authentication | Login and profile | ordinary HTTP login/logout, CSRF, opaque session cookie, session mount aspect, typed user context |
| Forms | Profile editor | `Form`, `FormCodec`, change validation, submit validation, field errors |
| Uploads | Document uploader | constraints, progress, cancellation, consumption, application-owned cleanup |
| Navigation | Search | typed query params, patch, replace-patch, navigate, `handleParams` |
| Collections | Activity stream | stream insert, delete, reset, limit, and application-owned durable state |
| Composition | Vote components | component-local state, self-targeted events, typed parent-to-component updates |
| Client interop | Browser integration | JS command composition, hook, typed server event, raw hook event |
| Lifecycle UX | Notifications | connected state, flash, title updates, and after-render hook |

Advanced parity cases, nested LiveViews, portals, crash/reconnect fixtures, external uploads, and exhaustive protocol variants remain outside this module and can be added later.

## Authentication

Authentication is self-contained and in memory. The module contains one documented demo account.

The login LiveView renders a normal HTML form that posts to an ordinary ZIO HTTP handler. The handler validates a one-time application CSRF token, verifies credentials, creates an opaque high-entropy cookie token, stores its hash in server state, and redirects to the protected profile route. The cookie is `HttpOnly`, `SameSite=Lax`, and scoped to `/`. Its `Secure` attribute comes from strict, explicit, trusted deployment configuration: `false` for local HTTP and `true` whenever the browser-facing endpoint is HTTPS.

The protected route group uses one session-level `LiveMountAspect`. During disconnected mount it authenticates the cookie and returns a non-secret public session identifier as signed claims plus a typed `CurrentSession`. During connected mount it reloads that public identifier from server state because the synthesized websocket mount request does not retain the original browser cookie. Logout validates a session-specific CSRF token, revokes the session, expires the cookie, and redirects home.

## Data And Effects

Most examples keep state in their LiveView model. Services are used only when state must outlive one connection or when the example specifically teaches service composition.

Expected failures are represented in the model and rendered with a correction or retry action. Unexpected service failures are logged with operation context and shown as generic recoverable errors. Examples do not expose stack traces or swallow causes.

Uploads use small limits. Consumed files are written under a process-specific temporary directory owned by an `UploadStore`; users can delete them and a scoped finalizer removes the directory at shutdown.

Streams keep queryable state in the application model and store the opaque `LiveStream` handle separately. Components demonstrate only currently supported communication: component-local events, parent-to-component messages, and `sendUpdate` props.

## Presentation And Documentation

Reuse the current Tailwind and DaisyUI asset pipeline without new JavaScript dependencies. A persistent shell groups examples by category. Every page starts with a short description of the APIs it demonstrates.

`examples/README.md` documents startup, port configuration, demo credentials, the catalog-to-source map, data lifetime, security boundaries, and a manual smoke checklist. The root README points learners to `examples` rather than `e2eApp`.

## Verification

Add ZIO Test coverage only where it materially protects independently testable logic:

- authentication token, CSRF, session, and revocation behavior
- typed profile form decoding and error accumulation

All other examples are protected by compilation, asset bundling, the existing Scalive test suite, and a documented manual smoke pass. Browser automation is deferred.

## Constraints

- Use Scala 3.7.3 and the repository's existing Mill, ZIO, Tailwind, and DaisyUI versions.
- Use public `scalive.*` APIs only.
- Do not add a database, OAuth provider, browser test framework, or JavaScript dependency.
- Existing counter, list, and todo prototypes are disposable; preserve only ideas that remain pedagogically useful.
- Keep exhaustive compatibility fixtures in `e2eApp`.
- Do not add backward compatibility for the singular `example` module; Scalive is Alpha.
