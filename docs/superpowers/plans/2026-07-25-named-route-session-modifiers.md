# Named Route And Session Modifiers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace routing `@@` overloads and router modifier wrappers with one discoverable named-method API while preserving builder behavior and type safety.

**Architecture:** Keep the existing immutable route, session, params, and router builders. First add compile-tested named entry points with the same builder transitions, then migrate all executable call sites and remove the symbolic and wrapper APIs, and finally update current public documentation.

**Tech Stack:** Scala 3.7.3, ZIO 2, ZIO HTTP 3, ZIO Test, Mill, Scalafmt, Scalafix

## Global Constraints

- Preserve runtime behavior, builder return types, environment intersections, context accumulation, context projection, layout order, root-layout replacement, and router defaults.
- Expose only `withMountAspect`, `withLayout`, `withRootLayout`, `withSocketPath`, and `withTokenConfig`; do not retain `@@` aliases or deprecations.
- `withSocketPath` accepts `PathCodec[Unit]`; `withTokenConfig` accepts `TokenConfig`.
- Remove `LiveSocketMount`, `LiveTokenConfig`, `Live.socketAt`, and `Live.tokenConfig`.
- Do not redesign route application, `->`, layouts, mount aspects, tokens, or socket transport.
- Do not rewrite historical files under `doc/superpowers/specs` or `doc/superpowers/plans` merely to remove old API references.

---

### Task 1: Add The Named Builder API

**Files:**
- Modify: `scalive/test/src/scalive/LiveRoutesTypeSafetySpec.scala`
- Modify: `scalive/src/scalive/routing/LiveRouteDsl.scala`

**Interfaces:**
- Consumes: Existing `LiveMountAspect`, `LiveLayout`, `LiveRootLayout`, `PathCodec[Unit]`, `TokenConfig`, and immutable builder state.
- Produces: Named modifier methods on every currently supported route, session, params, and router stage. The old API remains temporarily available until Task 2.

- [ ] **Step 1: Add a compile-time test covering every supported stage**

Append this test before the closing test in `LiveRoutesTypeSafetySpec.spec`:

```scala
test("named modifiers compile across supported builder stages") {
  val errors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.*
    import zio.http.codec.PathCodec
    import zio.json.*

    final case class Claims(value: String) derives JsonCodec
    final case class User(name: String)
    final case class Section(name: String)

    val routeUserAspect = LiveMountAspect.fromRequest[Any, Unit, Claims, User](
      _ => ZIO.succeed(Claims("user") -> User("disconnected")),
      (_, _) => ZIO.succeed(User("connected"))
    )
    val routeSectionAspect = LiveMountAspect.make[Any, Unit, User, Claims, Section](
      (_, user) => ZIO.succeed(Claims(user.name) -> Section("disconnected")),
      (_, _, _) => ZIO.succeed(Section("connected"))
    )
    val sessionUserAspect = LiveMountAspect.fromRequest[Any, Any, Claims, User](
      _ => ZIO.succeed(Claims("user") -> User("disconnected")),
      (_, _) => ZIO.succeed(User("connected"))
    )
    val sessionSectionAspect = LiveMountAspect.make[Any, Any, User, Claims, Section](
      (_, user) => ZIO.succeed(Claims(user.name) -> Section("disconnected")),
      (_, _, _) => ZIO.succeed(Section("connected"))
    )

    val anyLayout = LiveLayout.identity
    val anyRoot = LiveRootLayout.identity
    val routeUserLayout = LiveLayout[Unit, User]((content, _) => content)
    val routeUserRoot = LiveRootLayout[Unit, User]("route-user-root")((content, _) => content)
    val sessionUserLayout = LiveLayout[Any, User]((content, _) => content)
    val sessionUserRoot = LiveRootLayout[Any, User]("session-user-root")((content, _) => content)

    val seedWithAspect = live.withMountAspect(routeUserAspect)
    val seedWithLayout = live.withLayout(anyLayout)
    val seedWithRoot = live.withRootLayout(anyRoot)

    val routeBuilder = seedWithAspect
      .withLayout(routeUserLayout)
      .withRootLayout(routeUserRoot)
      .withMountAspect(routeSectionAspect)

    val paramsBuilder = live.params
      .withMountAspect(routeUserAspect)
      .withLayout(routeUserLayout)
      .withRootLayout(routeUserRoot)

    val sessionSeedWithAspect = Live.session("admin").withMountAspect(sessionUserAspect)
    val sessionSeedWithLayout = Live.session("layout").withLayout(anyLayout)
    val sessionSeedWithRoot = Live.session("root").withRootLayout(anyRoot)
    val sessionBuilder = sessionSeedWithAspect
      .withLayout(sessionUserLayout)
      .withRootLayout(sessionUserRoot)
      .withMountAspect(sessionSectionAspect)

    val router = Live.router
      .withLayout(anyLayout)
      .withRootLayout(anyRoot)
      .withSocketPath(PathCodec.empty / "socket")
      .withTokenConfig(TokenConfig.default)
  """)

  assertTrue(errors.isEmpty)
},
test("router-only modifiers are unavailable on routes and sessions") {
  val routeErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.http.codec.PathCodec
    val route = live.withSocketPath(PathCodec.empty / "socket")
  """)
  val sessionErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    val session = Live.session("admin").withTokenConfig(TokenConfig.default)
  """)

  assertTrue(routeErrors.nonEmpty, sessionErrors.nonEmpty)
},
```

- [ ] **Step 2: Run the focused test and verify the API is absent**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveRoutesTypeSafetySpec`

Expected: FAIL in `named modifiers compile across supported builder stages`, with errors reporting that methods such as `withMountAspect` and `withSocketPath` are not members of the builders.

- [ ] **Step 3: Add named route modifier entry points alongside the temporary symbolic methods**

In `LiveRouteSeed`, `LiveRouteBuilder`, and `LiveRouteParamsBuilder`, add methods with these exact declarations:

```scala
// LiveRouteSeed
def withMountAspect[R, In, Claims, Out, Result](
  aspect: LiveMountAspect[R, A, In, Claims, Out]
)(using append: ContextAppend.Aux[In, Out, Result]): LiveRouteBuilder[R, A, In, Result]

def withLayout[Ctx](layout: LiveLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
  base[Ctx].withLayout(layout)

def withRootLayout[Ctx](layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[Any, A, Ctx, Ctx] =
  base[Ctx].withRootLayout(layout)

// LiveRouteBuilder
def withMountAspect[R1, Claims, Out, Result](
  aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
)(using append: ContextAppend.Aux[Ctx, Out, Result]): LiveRouteBuilder[R & R1, A, Need, Result]

def withLayout(layout: LiveLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx]

def withRootLayout(layout: LiveRootLayout[A, Ctx]): LiveRouteBuilder[R, A, Need, Ctx]

// LiveRouteParamsBuilder
def withMountAspect[R1, Claims, Out, Result](
  aspect: LiveMountAspect[R1, A, Ctx, Claims, Out]
)(using append: ContextAppend.Aux[Ctx, Out, Result])
  : LiveRouteParamsBuilder[R & R1, A, Need, Result, Params, Capability]

def withLayout(layout: LiveLayout[A, Ctx])
  : LiveRouteParamsBuilder[R, A, Need, Ctx, Params, Capability]

def withRootLayout(layout: LiveRootLayout[A, Ctx])
  : LiveRouteParamsBuilder[R, A, Need, Ctx, Params, Capability]
```

Copy the corresponding existing `@@` body into each named method byte-for-byte except for seed forwarding calls, which call `withLayout` and `withRootLayout`. Keep the old `@@` overloads and their `@targetName` annotations temporarily so existing sources compile until Task 2.

- [ ] **Step 4: Add named session modifier entry points alongside the temporary symbolic methods**

Apply these exact declarations:

```scala
// LiveSessionSeed
def withMountAspect[R, Claims, Out, Result](
  aspect: LiveMountAspect[R, Any, Any, Claims, Out]
)(using ContextAppend.Aux[Any, Out, Result]): LiveSessionBuilder[R, Result]

def withLayout(layout: LiveLayout[Any, Any]): LiveSessionBuilder[Any, Any]

def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveSessionBuilder[Any, Any]

// LiveSessionBuilder
def withMountAspect[R1, Claims, Out, Result](
  aspect: LiveMountAspect[R1, Any, Ctx, Claims, Out]
)(using append: ContextAppend.Aux[Ctx, Out, Result]): LiveSessionBuilder[R & R1, Result]

def withLayout(layout: LiveLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx]

def withRootLayout(layout: LiveRootLayout[Any, Ctx]): LiveSessionBuilder[R, Ctx]
```

Copy the corresponding existing `@@` bodies so pipeline appends, context projection, and layout/root-layout transitions remain unchanged. Keep the old `@@` overloads and their `@targetName` annotations temporarily.

- [ ] **Step 5: Add direct router configuration methods**

Add these methods alongside the four temporary router `@@` overloads, retaining the existing immutable copies:

```scala
def withLayout(layout: LiveLayout[Any, Any]): LiveRouter[R] =
  LiveRouter(globalLayouts :+ layout, globalRootLayout, liveSocketMount, tokenConfig)

def withRootLayout(layout: LiveRootLayout[Any, Any]): LiveRouter[R] =
  LiveRouter(globalLayouts, layout, liveSocketMount, tokenConfig)

def withSocketPath(path: PathCodec[Unit]): LiveRouter[R] =
  LiveRouter(globalLayouts, globalRootLayout, path, tokenConfig)

def withTokenConfig(config: TokenConfig): LiveRouter[R] =
  LiveRouter(globalLayouts, globalRootLayout, liveSocketMount, config)
```

For this task, leave `LiveSocketMount`, `LiveTokenConfig`, `Live.socketAt`, and `Live.tokenConfig` in place so existing call sites still compile. Their removal is tested and performed in Task 2.

- [ ] **Step 6: Run the focused compile-time API test**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveRoutesTypeSafetySpec`

Expected: PASS, including the named-modifier and router-boundary tests plus existing environment/context inference tests.

- [ ] **Step 7: Review the Task 1 diff**

Run: `git diff --check && git diff -- scalive/src/scalive/routing/LiveRouteDsl.scala scalive/test/src/scalive/LiveRoutesTypeSafetySpec.scala`

Expected: no whitespace errors; named methods preserve the old builder bodies and type signatures.

---

### Task 2: Remove The Symbolic API And Migrate Executable Sources

**Files:**
- Modify: `scalive/test/src/scalive/LiveRoutesTypeSafetySpec.scala`
- Modify: `scalive/src/scalive/routing/LiveRouteDsl.scala`
- Modify: `scalive/test/src/scalive/CsrfProtectionSpec.scala`
- Modify: `scalive/test/src/scalive/FlashSpec.scala`
- Modify: `scalive/test/src/scalive/LiveMountAspectSpec.scala`
- Modify: `scalive/test/src/scalive/LiveRoutesLayoutSpec.scala`
- Modify: `example/src/Example.scala`
- Modify: `e2eApp/src/E2EApp.scala`

**Interfaces:**
- Consumes: Named methods added in Task 1.
- Produces: Executable Scala sources with no routing `@@` usage and no modifier wrapper API.

- [ ] **Step 1: Add compile-time rejection tests for removed entry points**

Append this test to `LiveRoutesTypeSafetySpec.spec`:

```scala
test("symbolic and wrapper route modifiers are unavailable") {
  val operatorErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    val route = live @@ LiveLayout.identity
  """)
  val socketWrapperErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    import zio.http.codec.PathCodec
    val mount = Live.socketAt(PathCodec.empty / "socket")
  """)
  val tokenWrapperErrors = scala.compiletime.testing.typeCheckErrors("""
    import scalive.*
    val config = Live.tokenConfig(TokenConfig.default)
  """)

  assertTrue(
    operatorErrors.nonEmpty,
    socketWrapperErrors.nonEmpty,
    tokenWrapperErrors.nonEmpty
  )
},
```

- [ ] **Step 2: Run the focused test and verify legacy APIs still exist**

Run: `mill --ticker false scalive.test.testOnly scalive.LiveRoutesTypeSafetySpec`

Expected: FAIL in `symbolic and wrapper route modifiers are unavailable` because each `typeCheckErrors` result is empty.

- [ ] **Step 3: Migrate all test call sites to named methods**

Use the receiver and argument types to apply these exact rewrites without changing expression order:

```scala
routeOrSession @@ aspect     -> routeOrSession.withMountAspect(aspect)
routeOrSession @@ layout     -> routeOrSession.withLayout(layout)
routeOrSession @@ rootLayout -> routeOrSession.withRootLayout(rootLayout)
router @@ layout             -> router.withLayout(layout)
router @@ rootLayout         -> router.withRootLayout(rootLayout)
router @@ Live.tokenConfig(config) -> router.withTokenConfig(config)
router @@ Live.socketAt(path)       -> router.withSocketPath(path)
```

Apply the rewrites in every test file listed in this task. Preserve modifier order, especially mixed chains such as:

```scala
val route =
  Live.session("layout")
    .withMountAspect(userAspect)
    .withLayout(sessionLayout)(
      (live / "orgs" / PathCodec.int("id"))
        .withMountAspect(orgAspect)
        .withLayout(routeLayout) {
          (id, _, user: User, org: Org) => view(s"view:$id:${user.name}:${org.name}")
        }
    )

val routes = Live.router
  .withTokenConfig(tokenConfig)
  .withLayout(globalLayout)(route)
```

- [ ] **Step 4: Migrate application and E2E call sites**

Use the named router/session API in `example/src/Example.scala` and `e2eApp/src/E2EApp.scala`:

```scala
Live.router.withRootLayout(RootLayout(assets))(
  ExampleRoutes.home    -> HomeLiveView(),
  ExampleRoutes.counter -> CounterLiveView(),
  ExampleRoutes.list    -> ListLiveView(),
  ExampleRoutes.todo    -> TodoLiveView()
)
```

```scala
Live.router.withRootLayout(rootLayout)(routes*)
Live.session("issue-3047").withLayout(Issue3047LiveView.Layout)(routes*)
```

Keep the actual route lists already present in `E2EApp.scala`; only replace their enclosing modifier syntax.

- [ ] **Step 5: Remove legacy declarations**

Delete from `LiveRouteDsl.scala`:

```scala
final case class LiveSocketMount(pathCodec: PathCodec[Unit])
final case class LiveTokenConfig(config: TokenConfig)
```

Delete `Live.socketAt` and `Live.tokenConfig` from `object Live`. Delete every temporary `infix def @@` overload and its associated `@targetName` annotation from route, session, params, and router builders, leaving the named methods added in Task 1 unchanged.

- [ ] **Step 6: Run focused routing and configuration suites**

Run:

```bash
mill --ticker false scalive.test.testOnly \
  scalive.LiveRoutesTypeSafetySpec \
  scalive.LiveRoutesLayoutSpec \
  scalive.LiveMountAspectSpec \
  scalive.CsrfProtectionSpec \
  scalive.FlashSpec
```

Expected: all selected suites PASS, including the new rejection test.

- [ ] **Step 7: Compile application modules**

Run: `mill --ticker false example.compile e2eApp.compile`

Expected: both modules compile successfully with the named router and session methods.

- [ ] **Step 8: Verify executable Scala sources contain no legacy API**

Run:

```bash
rg -n '@@|Live\.socketAt|Live\.tokenConfig|LiveSocketMount|LiveTokenConfig' \
  scalive/src scalive/test/src example/src e2eApp/src
```

Expected: only the intentional string snippets in `LiveRoutesTypeSafetySpec` that prove removed APIs do not compile. No production or executable test expression uses the old API.

---

### Task 3: Update Current Public Documentation

**Files:**
- Modify: `doc/public-api-reference.md`
- Modify: `doc/api-improvement-ideas.md`
- Modify: `doc/user-facing-api-assessment.md`
- Modify: `UPSTREAM_COMPATIBILITY.md`

**Interfaces:**
- Consumes: Final named API from Task 2.
- Produces: Public documentation that presents only named modifiers and records the discoverability finding as addressed.

- [ ] **Step 1: Update the public API reference**

In `doc/public-api-reference.md`:

- Remove `Live.socketAt` and `Live.tokenConfig` from the `object Live` signature block.
- Replace route seed, route builder, and params builder `@@` examples with `withMountAspect`, `withLayout`, and `withRootLayout` calls.
- Replace session examples with `Live.session(name).withMountAspect(aspect)`, `.withLayout(layout)`, and `.withRootLayout(rootLayout)`.
- Replace router examples with `.withLayout(layout)`, `.withRootLayout(rootLayout)`, `.withSocketPath(path)`, and `.withTokenConfig(config)`.
- Remove `LiveSocketMount` and `LiveTokenConfig` from supporting route types.

The resulting router section must show:

```scala
Live.router.withLayout(layout)
Live.router.withRootLayout(rootLayout)
Live.router.withSocketPath(path)
Live.router.withTokenConfig(config)
Live.router(route, routes*)
```

- [ ] **Step 2: Remove the completed backlog item**

Delete the complete `### Reconsider broad use of @@` section from `doc/api-improvement-ideas.md`, including its current issue and ideas bullets. Do not alter adjacent root-layout or async sections.

- [ ] **Step 3: Refresh the API assessment**

In `doc/user-facing-api-assessment.md`:

- Rewrite the token/session configuration inventory bullet to name `LiveRouter.withSocketPath`, `LiveRouter.withTokenConfig`, and named route/session modifiers.
- Remove the complete `### Medium - Discoverability - Route and session modifiers rely heavily on symbolic @@` finding, because the only named API resolves it.
- Refresh line-based evidence references to the new method declarations after formatting.

- [ ] **Step 4: Refresh compatibility terminology**

In the endpoint/socket configuration row of `UPSTREAM_COMPATIBILITY.md`, replace the `Live.socketAt(PathCodec[Unit])` statement with:

```text
`Live.router.withSocketPath(PathCodec[Unit])` configures the socket mount path; `TokenConfig` configures secret/maxAge.
```

- [ ] **Step 5: Search current public docs for stale API references**

Run:

```bash
rg -n '@@|Live\.socketAt|Live\.tokenConfig|LiveSocketMount|LiveTokenConfig' \
  README.md UPSTREAM_COMPATIBILITY.md doc/public-api-reference.md \
  doc/api-improvement-ideas.md doc/user-facing-api-assessment.md
```

Expected: no matches.

- [ ] **Step 6: Review documentation changes**

Run: `git diff --check && git diff -- README.md UPSTREAM_COMPATIBILITY.md doc/public-api-reference.md doc/api-improvement-ideas.md doc/user-facing-api-assessment.md`

Expected: no whitespace errors, no stale symbolic examples, and no unrelated documentation changes.

---

### Task 4: Format And Verify The Complete Change

**Files:**
- Modify: any Scala source reformatted by the project formatter

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: A formatted, fully tested change with no executable legacy modifier API.

- [ ] **Step 1: Run project formatting and fixes**

Run: `mill --ticker false __.reformat + __.fix`

Expected: command succeeds. Inspect formatter changes and retain only changes caused by files in this plan.

- [ ] **Step 2: Run the full test suite**

Run: `mill --ticker false __.test`

Expected: all modules and test suites PASS.

- [ ] **Step 3: Compile the examples and E2E application after formatting**

Run: `mill --ticker false example.compile e2eApp.compile`

Expected: both compile successfully.

- [ ] **Step 4: Run final legacy-API searches**

Run:

```bash
rg -n '@@|Live\.socketAt|Live\.tokenConfig|LiveSocketMount|LiveTokenConfig' \
  scalive/src scalive/test/src example/src e2eApp/src \
  README.md UPSTREAM_COMPATIBILITY.md doc/public-api-reference.md \
  doc/api-improvement-ideas.md doc/user-facing-api-assessment.md
```

Expected: only intentional `typeCheckErrors` source strings in `LiveRoutesTypeSafetySpec`; all other matches are absent.

- [ ] **Step 5: Inspect the final worktree diff**

Run: `git status --short && git diff --check && git diff --stat && git diff`

Expected: only files named by this plan plus formatter-adjusted versions of those files are changed; no whitespace errors or unrelated edits are present.

- [ ] **Step 6: Commit only if explicitly requested**

If the user requests a commit, inspect `git status`, `git diff`, and `git log --oneline -10`, stage only the files from this plan, and create a Conventional Commit:

```bash
git commit -m "feat(routing): replace symbolic route modifiers"
```
