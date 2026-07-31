# Scalive Examples

This module is one runnable catalog of focused Scalive application patterns. The
shared shell demonstrates server startup, bundled assets, layouts, typed routes,
service construction, and route composition; each page then concentrates on one
API lesson.

## Prerequisites

- Run commands from the repository root.
- Enter `nix develop` so the repository's JDK, Mill, Node.js, and npm versions are
  available. Equivalent local installations can also be used.
- Use a current browser with JavaScript enabled. Clipboard behavior depends on
  browser permission and secure-context rules.

Start the server:

```bash
mill examples.run
```

Open <http://localhost:8080/>. The first run installs the locked npm dependencies
and builds the JavaScript and CSS assets.

Set a different integer port with `SCALIVE_SERVER_PORT`:

```bash
SCALIVE_SERVER_PORT=9090 mill examples.run
```

Authentication cookies are not marked `Secure` by default so the demo works over
local HTTP. `SCALIVE_SECURE_COOKIES` is the strict, explicit, trusted deployment
and proxy signal for the cookie `Secure` attribute:

- Unset means `false`.
- Exact `true` or `false`, compared case-insensitively, is accepted.
- Whitespace, `1`, an empty value, and every other value fail application startup.
- `true` marks auth cookies `Secure`; use it whenever the browser-facing endpoint
  is HTTPS. Use `false` for plain local HTTP.
- The server does not infer browser-facing HTTPS from the request URL scheme.

For example, behind an HTTPS endpoint:

```bash
SCALIVE_SECURE_COOKIES=true mill examples.run
```

The single demo account is:

```text
Email:    alice@example.com
Password: scalive
```

## Catalog And Source Map

All paths below are relative to this `examples` directory. Route declarations are
centralized in [`ExamplesRoutes.scala`](src/scalive/examples/ExamplesRoutes.scala),
registration is explicit in
[`ExamplesApp.scala`](src/scalive/examples/ExamplesApp.scala), and catalog labels
live in [`ExampleCatalog.scala`](src/scalive/examples/ExampleCatalog.scala).

| Route                           | Principal source                                                                                                                                                                                                                                                                 | API lesson                                                                                                                |
| ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `GET /`                         | [`HomeLiveView.scala`](src/scalive/examples/HomeLiveView.scala), [`ExamplesApp.scala`](src/scalive/examples/ExamplesApp.scala), [`ExamplesRootLayout.scala`](src/scalive/examples/ExamplesRootLayout.scala), [`ExamplesLayout.scala`](src/scalive/examples/ExamplesLayout.scala) | `ZIOAppDefault`, tracked assets, root and live layouts, typed routes, and an eventless `LiveView`                         |
| `GET /state/shopping-cart`      | [`ShoppingCartLiveView.scala`](src/scalive/examples/state/ShoppingCartLiveView.scala)                                                                                                                                                                                            | Typed models and messages, synchronous immutable updates, derived totals, and keyed rendering                             |
| `GET /services/guestbook`       | [`GuestbookLiveView.scala`](src/scalive/examples/services/GuestbookLiveView.scala), [`Guestbook.scala`](src/scalive/examples/services/Guestbook.scala)                                                                                                                           | A route-level LiveView layer, inferred constructor dependencies, and state shared across connections                     |
| `GET /processing/subscriptions` | [`ClockLiveView.scala`](src/scalive/examples/processing/ClockLiveView.scala)                                                                                                                                                                                                     | A typed `SubscriptionKey` controlling `ZStream` start, replacement, and cancellation                                      |
| `GET /processing/async`         | [`AsyncReportLiveView.scala`](src/scalive/examples/processing/AsyncReportLiveView.scala)                                                                                                                                                                                         | `AsyncKey`, `AsyncValue`, typed success, failure, and cancellation messages, task replacement, and retry                  |
| `GET /auth/login`               | [`LoginLiveView.scala`](src/scalive/examples/auth/LoginLiveView.scala), [`AuthHttpRoutes.scala`](src/scalive/examples/auth/AuthHttpRoutes.scala)                                                                                                                                 | A normal HTML login form with automatic framework CSRF, bounded typed HTTP decoding, and HTTP-to-Live flash              |
| `GET /auth/profile`             | [`ProfileLiveView.scala`](src/scalive/examples/auth/ProfileLiveView.scala), [`AuthMountAspect.scala`](src/scalive/examples/auth/AuthMountAspect.scala)                                                                                                                           | A protected Live route, cookie authentication during disconnected mount, and claims-based connected mount resumption      |
| `GET /forms/profile`            | [`ProfileFormLiveView.scala`](src/scalive/examples/forms/ProfileFormLiveView.scala)                                                                                                                                                                                              | `Form`, `FormCodec`, accumulated path-specific validation, used fields, and typed submit values                           |
| `GET /uploads/documents`        | [`DocumentUploadLiveView.scala`](src/scalive/examples/uploads/DocumentUploadLiveView.scala), [`UploadStore.scala`](src/scalive/examples/uploads/UploadStore.scala)                                                                                                               | Upload constraints, validation, progress, cancellation, consumption, application storage, retry, and deletion             |
| `GET /navigation/search`        | [`SearchLiveView.scala`](src/scalive/examples/navigation/SearchLiveView.scala)                                                                                                                                                                                                   | Schema-derived query params, complete typed locations, `handleParams`, navigate, patch, and replace-patch                 |
| `GET /collections/activity`     | [`ActivityStreamLiveView.scala`](src/scalive/examples/collections/ActivityStreamLiveView.scala)                                                                                                                                                                                  | Queryable model state alongside an opaque `LiveStream`, with bounded insert, delete, and reset operations                 |
| `GET /components/voting`        | [`ComponentsLiveView.scala`](src/scalive/examples/components/ComponentsLiveView.scala), [`VoteComponent.scala`](src/scalive/examples/components/VoteComponent.scala)                                                                                                             | Stable component identity, component-local events, typed parent messages, and `sendUpdate` props                          |
| `GET /interop/browser`          | [`BrowserInteropLiveView.scala`](src/scalive/examples/interop/BrowserInteropLiveView.scala), [`app.js`](assets/js/app.js)                                                                                                                                                        | Composed client-only `JS`, a typed server-to-client event, a JavaScript hook, and validated raw hook replies              |
| `GET /lifecycle/notifications`  | [`NotificationsLiveView.scala`](src/scalive/examples/lifecycle/NotificationsLiveView.scala)                                                                                                                                                                                      | Connected state, client connection bindings, keyed flash, document titles, and an after-render hook                       |

Authentication also uses two ordinary HTTP endpoints in
[`AuthHttpRoutes.scala`](src/scalive/examples/auth/AuthHttpRoutes.scala):

The session and logout `RoutePattern` values are shared by HTTP dispatch and
their rendered `FormAction`s, so browser methods and paths cannot drift apart.

| Endpoint             | Lesson                                                                                                      |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| `POST /auth/session` | Decode a bounded typed form, validate browser-bound framework CSRF and credentials, then redirect with a session or generic typed flash |
| `POST /auth/logout`  | Validate framework CSRF, revoke the server session, expire the cookie, and redirect home                    |

## Data Lifetime

There is no database or durable persistence in this module.

- Shopping cart, clock, async report, form, search, activity, component, browser
  interop, and notification state belong to one LiveView connection and reset when
  that example is mounted again.
- The guestbook is one process-wide in-memory service. Its entries are shared by
  browser connections but disappear when the examples server restarts.
- Sessions are bounded, in-memory server records and disappear on restart. An
  authenticated session lasts 30 minutes and can be revoked by logout. The default
  is `maxSessions = 1024`. Auth operations opportunistically prune expired records;
  an insertion at capacity deterministically evicts the oldest session.
- Stored documents are shared through one process-scoped `UploadStore`. Each
  process creates its own `scalive-documents-*` temporary directory. UI deletion
  removes an individual file; the layer finalizer recursively removes the whole
  directory during scoped shutdown. An abrupt process or machine failure can leave
  temporary files for normal operating-system cleanup.

## Authentication Boundary

The auth example teaches flow and API composition: an ordinary HTTP login/logout
boundary, browser-bound signed framework CSRF, opaque high-entropy cookies, hashed token
lookup, `HttpOnly`, `SameSite=Lax`, configurable `Secure`, revocation, signed
non-secret claims, typed HTTP-to-Live flash, and typed route context. The login boundary preserves repeated
URL-encoded fields during transport decoding, then validates singular rooted
`login[...]` fields through `FormCodec`; malformed, oversized, and wrong-content-type
requests remain distinct from domain validation failures. Invalid credentials redirect
to the parameterless login route and the next Live render consumes the generic flash.

It is not a production identity system. It has one hard-coded account and no
database, password hashing, account management, rate limiting, audit trail, TLS
termination, proxy policy, key rotation, or distributed session store. Treat it as
an educational starting point, not a security deployment template.

Advanced parity and protocol cases remain in `e2eApp`, including exhaustive
fixtures that are useful as compatibility evidence but are not always recommended
application style. Nested LiveViews, portals, crash/reconnect demonstrations,
external uploads, and exhaustive upload, stream, and protocol variants are deferred
to `e2eApp` or future examples.

## Manual Smoke Checklist

Run the server, keep its console visible for unexpected errors, and use a fresh
browser profile when checking authentication.

- [x] **Shell and catalog, `/`:** confirm every catalog card opens its route. At a
      desktop width, confirm the left navigation is sticky and the content remains
      readable. At a mobile width, confirm navigation becomes a horizontally scrollable
      header and no page causes horizontal document overflow.
- [x] **Shopping cart, `/state/shopping-cart`:** add one product twice and another
      once; confirm quantities, item count, subtotals, and total. Remove one item, then
      clear the cart and confirm the empty state and disabled Clear button.
- [x] **Guestbook, `/services/guestbook`:** add both sample notes, open the route in
      a second browser tab, and confirm Reload shows the process-shared entries there.
- [x] **Clock, `/processing/subscriptions`:** start the one-second stream and watch
      the count advance; replace it and observe faster ticks; cancel it and confirm the
      count stops changing.
- [x] **Async report, `/processing/async`:** confirm the initial empty state. Run a
      success through loading to its result, run a failure through loading to the error
      state, use Retry to reach a retried result, start slow work then Replace it and
      confirm the replacement wins, and start work then Cancel to see the cancelled
      state.
- [x] **Auth CSRF and invalid login, `/auth/login`:** clear site cookies and open the
      route directly; confirm the login form renders without an intermediate redirect.
      Submit a wrong password and confirm only the generic invalid sign-in message appears.
- [x] **Auth login, profile, and logout:** sign in with `alice@example.com` /
      `scalive`, confirm `/auth/profile` shows Alice and a public session ID, then use
      **Sign out and revoke session** and confirm the redirect to `/`. Reopen
      `/auth/profile` and confirm it redirects to login. Also confirm an unauthenticated
      fresh browser cannot open `/auth/profile` directly.
- [x] **Profile form, `/forms/profile`:** type and erase content in required fields,
      or submit the blank form, to mark fields used and reveal their errors; enter a
      malformed email and a biography over 500 characters to confirm path-specific
      errors; then submit valid values and confirm the saved-profile success alert.
- [x] **Uploads, `/uploads/documents`:** select a disallowed extension, a file over
      1 MiB, and more than two files to confirm each constraint message. Select a valid
      `.txt` or `.md` file and observe progress, cancel an in-progress or completed
      entry, upload again, save the completed document into Stored documents, and delete
      it. Confirm the stored row disappears. **Residual gap:** the **Retry storage**
      action is not covered by this normal smoke path because displaying it requires an
      intentionally induced filesystem or `UploadStore` failure; verify it separately
      only in an environment where that failure can be introduced safely.
- [x] **Search, `/navigation/search`:** submit `streams` and confirm the typed query
      appears in the URL and filters results. Clear with replace-patch. Search with no
      filter, move to the next and previous pages with patch links, and confirm a
      non-positive `page` query displays page 1.
- [x] **Activity stream, `/collections/activity`:** insert enough activities to
      exceed five visible stream rows while Durable history continues to grow. Delete a
      visible row, then reset the stream and confirm only the latest three model entries
      are rendered.
- [x] **Voting components, `/components/voting`:** vote independently in both
      components and reset one without changing the other. Send a typed parent vote to
      the Scala component, then send updated props and confirm its title changes while
      its local count is preserved.
- [x] **Browser interop, `/interop/browser`:** run the composed command twice and
      confirm the placeholder hides, panel shows, and detail toggles. Deny clipboard
      permission or use a context without clipboard support and confirm the failure
      state; allow clipboard permission in a supported secure context, retry, confirm
      success, and verify the sample text is on the clipboard.
- [x] **Notifications, `/lifecycle/notifications`:** confirm the connected badge,
      put and clear the keyed notification, request attention and verify both the shown
      current title and browser tab title change, then restore the title. In browser
      developer tools run `window.liveSocket.disconnect()` and
      `window.liveSocket.connect()` to confirm the disconnected and connected badges
      follow socket state; check the server console for the connected after-render debug
      log.
