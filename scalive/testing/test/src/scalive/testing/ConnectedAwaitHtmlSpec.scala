package scalive.testing

import java.time.Duration
import java.util.concurrent.{ConcurrentLinkedQueue, TimeoutException}
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*

import org.jsoup.Jsoup
import zio.*
import zio.http.{Request, URL}
import zio.test.*

import scalive.*

object ConnectedAwaitHtmlSpec extends ZIOSpecDefault:
  private enum Msg:
    case First, Second, Third

  final private case class Fixture(
    view: LiveView[Msg, String],
    first: Promise[Nothing, Unit],
    second: Promise[Nothing, Unit],
    third: Promise[Nothing, Unit]):
    def open: Task[ConnectedClient[Any]] =
      ConnectedRender.open(scalive.Live.router(scalive.live(view)), config, Request.get(URL.root))

  private val config = ZioHttpConfig(
    "01234567890123456789012345678901",
    Duration.ofMinutes(30),
    secureCookie = false,
    allowedWebSocketOrigins = Set(WebSocketOrigin.https("scalive.test"))
  ).toOption.get

  private val deadline = Duration.ofSeconds(10)

  private def fixture: UIO[Fixture] =
    for
      first  <- Promise.make[Nothing, Unit]
      second <- Promise.make[Nothing, Unit]
      third  <- Promise.make[Nothing, Unit]
      view = new LiveView[Msg, String]:
               private val firstKey  = AsyncKey[Unit]("await-html-first")
               private val secondKey = AsyncKey[Unit]("await-html-second")
               private val thirdKey  = AsyncKey[Unit]("await-html-third")

               def mount(ctx: MountContext) = ctx.connection match
                 case Connection.Disconnected         => ZIO.succeed("initial")
                 case Connection.Connected(connected) =>
                   for
                     _ <- connected.async.start(firstKey)(first.await)(_ => Msg.First)
                     _ <- connected.async.start(secondKey)(second.await)(_ => Msg.Second)
                     _ <- connected.async.start(thirdKey)(third.await)(_ => Msg.Third)
                   yield "initial"

               def handleMessage(model: String, ctx: MessageContext) =
                 case Msg.First  => ZIO.succeed("first")
                 case Msg.Second => ZIO.succeed("second")
                 case Msg.Third  => ZIO.succeed("third")

               override def view(model: Signal[String]) =
                 div(span(dataAttr("phase") := "", model))
    yield Fixture(view, first, second, third)

  private def phase(html: String): String =
    Jsoup.parseBodyFragment(html).selectFirst("[data-phase]").text()

  // Observe predicates rather than competing with awaitHtml for notification consumption.
  final private class Observations:
    private val values = new ConcurrentLinkedQueue[String]()

    def check(predicate: String => Boolean): String => Boolean = html =>
      val _ = values.add(html)
      predicate(html)

    def awaitPhase(expected: String): UIO[String] = ZIO.suspendSucceed {
      values.iterator().asScala.find(html => phase(html) == expected) match
        case Some(html) => ZIO.succeed(html)
        case None       => ZIO.yieldNow *> awaitPhase(expected)
    }

  private def assertTimeout(
    exit: Exit[Throwable, String],
    description: String,
    lastHtml: String,
    timeout: Duration = deadline
  ): TestResult =
    val failure = exit.causeOption.flatMap(_.failureOption)
    assertTrue(
      failure.exists(_.isInstanceOf[TimeoutException]),
      failure.exists(_.getMessage.contains(description)),
      failure.exists(_.getMessage.contains(timeout.toString)),
      failure.exists(_.getMessage.contains(lastHtml)),
      exit.causeOption.exists(_.defects.isEmpty)
    )

  private def assertClosed(exit: Exit[Throwable, String]): TestResult =
    assertTrue(
      exit.causeOption.exists(_.failureOption.exists(!_.isInstanceOf[TimeoutException])),
      exit.causeOption.exists(_.defects.isEmpty)
    )

  def spec = suite("ConnectedAwaitHtmlSpec")(
    test("checks the latest retained semantic HTML immediately and returns it exactly") {
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          initial   <- connected.html
          immediate <- connected.awaitHtml("initial phase", deadline)(phase(_) == "initial")
          // Complete the correlated action before starting the next notification consumer.
          _        <- connected.send(Msg.Second)
          retained <- connected.html
          observed <- ZIO.succeed(new AtomicReference[String]())
          result   <- connected.awaitHtml("retained second phase", deadline) { html =>
                      observed.set(html)
                      phase(html) == "second"
                    }
        yield assertTrue(immediate == initial, result == retained, result == observed.get())
      }
    },
    test("rechecks each uncorrelated diff and returns the exact successful HTML") {
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml("third phase", deadline)(observed.check(phase(_) == "third"))
                       .forkScoped
          _           <- observed.awaitPhase("initial")
          _           <- fixture.first.succeed(())
          first       <- observed.awaitPhase("first")
          afterFirst  <- waiting.poll
          _           <- fixture.second.succeed(())
          second      <- observed.awaitPhase("second")
          afterSecond <- waiting.poll
          _           <- fixture.third.succeed(())
          result      <- waiting.join
          successful  <- observed.awaitPhase("third")
        yield assertTrue(
          first != second,
          afterFirst.isEmpty,
          afterSecond.isEmpty,
          result == successful,
          phase(result) == "third"
        )
      }
    },
    test("can remain silent beyond awaitDiff's five-second timeout before succeeding") {
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml("first phase after silence", deadline)(
                         observed.check(phase(_) == "first")
                       ).forkScoped
          _       <- observed.awaitPhase("initial")
          _       <- TestClock.adjust(6.seconds)
          pending <- waiting.poll
          _       <- fixture.first.succeed(())
          result  <- waiting.join
        yield assertTrue(pending.isEmpty, phase(result) == "first")
      }
    },
    test("uses one overall deadline across intermediate diffs and reports the last HTML") {
      val description = "missing final phase after intermediate output"
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml(description, deadline)(observed.check(_ => false)).forkScoped
          _        <- observed.awaitPhase("initial")
          _        <- TestClock.adjust(3.seconds)
          _        <- fixture.first.succeed(())
          _        <- observed.awaitPhase("first")
          _        <- TestClock.adjust(3.seconds)
          _        <- fixture.second.succeed(())
          lastHtml <- observed.awaitPhase("second")
          _        <- TestClock.adjust(3.seconds)
          pending  <- waiting.poll
          _        <- TestClock.adjust(1.second)
          exit     <- waiting.await
        yield assertTrue(pending.isEmpty) && assertTimeout(exit, description, lastHtml)
      }
    },
    test("times out without any output and includes the initial HTML in diagnostics") {
      val description = "missing output on a silent view"
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml(description, deadline)(observed.check(_ => false)).forkScoped
          initial <- observed.awaitPhase("initial")
          _       <- TestClock.adjust(deadline)
          exit    <- waiting.await
        yield assertTimeout(exit, description, initial)
      }
    },
    test("a live-clock timeout retains the description and last observed HTML") {
      val description = "silent view with a live-clock deadline"
      val timeout     = Duration.ofSeconds(1)
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          initial   <- connected.html
          exit      <- zio.test.Live
                    .live(
                      connected.awaitHtml(description, timeout)(_ => false)
                    ).exit
        yield assertTimeout(exit, description, initial, timeout)
      }
    },
    test("propagates an initial predicate exception unchanged as a Task failure") {
      val error = IllegalArgumentException("initial predicate failed")
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          exit      <- connected.awaitHtml("throw immediately", deadline)(_ => throw error).exit
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(error),
          exit.causeOption.exists(_.defects.isEmpty)
        )
      }
    },
    test("propagates a predicate exception after a diff unchanged as a Task failure") {
      val error = IllegalStateException("updated predicate failed")
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml("throw after output", deadline)(observed.check { html =>
                         if phase(html) == "first" then throw error
                         else false
                       }).forkScoped
          _    <- observed.awaitPhase("initial")
          _    <- fixture.first.succeed(())
          exit <- waiting.await
        yield assertTrue(
          exit.causeOption.flatMap(_.failureOption).contains(error),
          exit.causeOption.exists(_.defects.isEmpty)
        )
      }
    },
    test("fails promptly when its nested view leaves while the root remains joined") {
      ZIO.scoped {
        for
          fixture <- fixture
          parent = new LiveView.Eventless[Unit]:
                     def mount(ctx: MountContext)           = ZIO.unit
                     override def view(model: Signal[Unit]) =
                       div(liveView("await-html-child", fixture.view))
          root     <- ConnectedRender.join(parent)
          nested   <- root.joinNested("await-html-child")
          observed <- ZIO.succeed(new Observations)
          before   <- Clock.instant
          waiting  <- nested
                       .awaitHtml("retired child", 1.hour)(observed.check(_ => false)).forkScoped
          _          <- observed.awaitPhase("initial")
          _          <- TestClock.adjust(Duration.ZERO)
          _          <- nested.leave
          exit       <- waiting.await
          after      <- Clock.instant
          rootJoined <- root.isJoined
        yield assertClosed(exit) && assertTrue(before == after, rootJoined)
      }
    },
    test("fails promptly when a server-initiated handler failure retires the view") {
      val error = IllegalStateException("async completion handler failed")
      ZIO.scoped {
        for
          release <- Promise.make[Nothing, Unit]
          view = new LiveView[Unit, Unit]:
                   private val retire           = AsyncKey[Unit]("await-html-retire-after-join")
                   def mount(ctx: MountContext) = ctx.connection match
                     case Connection.Disconnected         => ZIO.unit
                     case Connection.Connected(connected) =>
                       connected.async.start(retire)(release.await)(_ => ()).unit
                   def handleMessage(model: Unit, ctx: MessageContext) =
                     case () => ZIO.fail(error)
                   override def view(model: Signal[Unit]) =
                     div(span(dataAttr("phase") := "", "initial"))
          connected <- ConnectedRender.join(view)
          observed  <- ZIO.succeed(new Observations)
          waiting   <- connected
                       .awaitHtml("server-retired view", 1.hour)(observed.check(_ => false))
                       .forkScoped
          _      <- observed.awaitPhase("initial")
          _      <- TestClock.adjust(Duration.ZERO)
          _      <- release.succeed(())
          exit   <- waiting.await
          joined <- connected.isJoined
        yield assertClosed(exit) && assertTrue(!joined)
      }
    },
    test("waiting on a view retired by followed navigation fails without following the new view") {
      val source = new LiveView[Unit, Unit]:
        def mount(ctx: MountContext)                        = ZIO.unit
        def handleMessage(model: Unit, ctx: MessageContext) =
          case () => ctx.nav.pushNavigateUnsafe("/next").as(model)
        override def view(model: Signal[Unit]) =
          div(
            span(dataAttr("phase")      := "", "initial"),
            button(dataAttr("navigate") := "", on.click(()), "Next")
          )
      val destination = new LiveView.Eventless[Unit]:
        def mount(ctx: MountContext)           = ZIO.unit
        override def view(model: Signal[Unit]) = div("destination")
      val application = scalive.Live.router(
        scalive.live(source),
        (scalive.live / "next")(destination)
      )

      ZIO.scoped {
        for
          root   <- ConnectedRender.join(application, config, Request.get(URL.root))
          action <- root.click("[data-navigate]")
          next   <- action match
                    case ConnectedAction.LiveNavigation(navigation) => navigation.follow
                    case other => ZIO.fail(Exception(s"Expected live navigation, got $other."))
          exit       <- root.awaitHtml("replaced source view", 1.hour)(_ => true).exit
          rootJoined <- root.isJoined
          nextJoined <- next.isJoined
        yield assertClosed(exit) && assertTrue(!rootJoined, nextJoined)
      }
    },
    test("fails promptly when its physical client disconnects") {
      ZIO.scoped {
        for
          fixture   <- fixture
          client    <- fixture.open
          connected <- client.join
          observed  <- ZIO.succeed(new Observations)
          before    <- Clock.instant
          waiting   <-
            connected
              .awaitHtml("disconnected view", 1.hour)(observed.check(_ => false)).forkScoped
          _     <- observed.awaitPhase("initial")
          _     <- TestClock.adjust(Duration.ZERO)
          _     <- client.disconnect
          exit  <- waiting.await
          after <- Clock.instant
        yield assertClosed(exit) && assertTrue(before == after)
      }
    },
    test("already left and disconnected views fail in Task even for a matching predicate") {
      ZIO.scoped {
        for
          fixture    <- fixture
          leftView   <- ConnectedRender.join(fixture.view)
          _          <- leftView.leave
          left       <- leftView.awaitHtml("already left", 1.hour)(_ => true).exit
          client     <- fixture.open
          closedView <- client.join
          _          <- client.disconnect
          closed     <- closedView.awaitHtml("already disconnected", 1.hour)(_ => true).exit
        yield assertClosed(left) && assertClosed(closed)
      }
    },
    test("an interrupted waiter does not consume output needed by the next waiter") {
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          abandoned <- ZIO.succeed(new Observations)
          first     <- connected
                     .awaitHtml("cancelled wait", deadline)(abandoned.check(_ => false)).forkScoped
          _ <- abandoned.awaitPhase("initial")
          // Let the waiter suspend before interrupting, without advancing test time.
          _         <- TestClock.adjust(Duration.ZERO)
          cancelled <- first.interrupt
          observed  <- ZIO.succeed(new Observations)
          next      <- connected
                    .awaitHtml("first phase after cancellation", deadline)(
                      observed.check(phase(_) == "first")
                    ).forkScoped
          _      <- observed.awaitPhase("initial")
          _      <- fixture.first.succeed(())
          result <- next.join
        yield assertTrue(cancelled.isInterrupted, phase(result) == "first")
      }
    },
    test("a timed-out waiter does not consume output needed by the next waiter") {
      val description = "abandoned by timeout"
      ZIO.scoped {
        for
          fixture   <- fixture
          connected <- ConnectedRender.join(fixture.view)
          abandoned <- ZIO.succeed(new Observations)
          first     <- connected
                     .awaitHtml(description, deadline)(abandoned.check(_ => false)).forkScoped
          initial  <- abandoned.awaitPhase("initial")
          _        <- TestClock.adjust(deadline)
          timedOut <- first.await
          observed <- ZIO.succeed(new Observations)
          next     <- connected
                    .awaitHtml("first phase after timeout", deadline)(
                      observed.check(phase(_) == "first")
                    ).forkScoped
          _      <- observed.awaitPhase("initial")
          _      <- fixture.first.succeed(())
          result <- next.join
        yield assertTimeout(timedOut, description, initial) && assertTrue(phase(result) == "first")
      }
    }
  ) @@ TestAspect.timeout(15.seconds)
end ConnectedAwaitHtmlSpec
