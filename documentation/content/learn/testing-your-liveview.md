{%
title = "Testing your LiveView"
description = "Add a test module to the quick-start project and test the counter, the sign-up form, and the service-backed thermostat."
order = 8
section = learn
%}

## Add A Test Module {#add-a-test-module}

Scalive's test support is the optional `dev.scalive::scalive-testing` artifact.
Add a test module inside `app` in `build.mill`. It uses the same snapshot
repository and version as the application:

```scala unverified
//| mill-version: 1.1.8
package build

import mill.*, scalalib.*

object app extends ScalaModule:
  def scalaVersion = "3.8.4"
  def mainClass = Some("quickstart.Main")

  def repositories = Seq(
    "https://central.sonatype.com/repository/maven-snapshots"
  )

  def mvnDeps = Seq(
    mvn"dev.scalive::scalive:{{scaliveSnapshotVersion}}"
  )

  object test extends ScalaTests with TestModule.ZioTest:
    def zioTestVersion = "2.1.26"

    def mvnDeps = Seq(
      mvn"dev.scalive::scalive-testing:{{scaliveSnapshotVersion}}"
    )
end app
```

`TestModule.ZioTest` runs tests with ZIO Test. The nested `test` module depends
on `app`, so tests can use the application's classes. Put test sources under
`app/test/src`, in the same package as the application:

```text
scalive-quick-start/
└── app/
    ├── src/
    │   └── quickstart/
    └── test/
        └── src/
            └── quickstart/
                ├── CounterLiveViewSpec.scala
                ├── SignupLiveViewSpec.scala
                └── ThermostatLiveViewSpec.scala
```

## Write A First Test {#write-a-first-test}

Create `app/test/src/quickstart/CounterLiveViewSpec.scala`:

@:sourceRegion(documentation/fixtures/learn/test/src/quickstart/CounterLiveViewSpec.scala, learn-counter-test)

The spec extends `ZIOSpecDefault`, groups tests with `suite`, and asserts with
`assertTrue`. @:apiSymbol(def:scalive.testing.ConnectedRender.join)`ConnectedRender.join`@:@
mounts the LiveView, renders its first HTML, and then connects it through the
same protocol handling a browser uses, without starting a server.
`clickButton` finds the button labeled **Increase** in the latest HTML, sends
its click, and waits for the next render. `text` reads one element by CSS
selector, here the counter's `<output>`. `ZIO.scoped` closes the connected view
when the test ends.

Run the tests from the project root:

```bash
mill app.test
```

Mill compiles the application and its tests, then reports
`increases the count, then resets it` as passed.

## Test A Form {#test-a-form}

Create `app/test/src/quickstart/SignupLiveViewSpec.scala`:

@:sourceRegion(documentation/fixtures/learn/test/src/quickstart/SignupLiveViewSpec.scala, learn-signup-test)

`submitForm` selects the form by CSS selector and sends its submit event with
the given fields, as the browser would. The field names come from the form
definition, such as `Signup.Name.name`, so a renamed field cannot leave the test
behind. `html` returns the latest rendered HTML, and
@:apiSymbol(object:scalive.testing.RenderedHtml)`RenderedHtml`@:@ queries it by
CSS selector.

## Test A Page That Uses A Service {#test-a-service-backed-page}

The thermostat takes its `Thermostat` service as a constructor argument, so a
test can pass its own implementation. Create
`app/test/src/quickstart/ThermostatLiveViewSpec.scala`:

@:sourceRegion(documentation/fixtures/learn/test/src/quickstart/ThermostatLiveViewSpec.scala, learn-thermostat-test)

The fake sensor reads a `Ref`, so the test decides each temperature. The reading
arrives after the page connects, not as the reply to an action, so the test
waits with `awaitHtml` until the rendered HTML satisfies a condition. If the
condition never holds, the test fails after the timeout and reports the last
HTML.

ZIO Test normally replaces the clock with a test clock that moves only when a
test adjusts it, so a timeout would never expire. `TestAspect.withLiveClock`
makes this suite use real time. It does not slow the tests down: the ten-second
refresh timer never fires because each test finishes first.

Run `mill app.test` again. All five tests pass.

## Go Further {#go-further}

The [testing guide](../guides/testing.md#choose-the-test-boundary) covers the
first HTTP response, routes with sessions and cookies, navigation, uploads,
reconnects, and when to use a real browser instead. Next,
[How the pieces fit](how-the-pieces-fit.md) recaps the application you have
built.
