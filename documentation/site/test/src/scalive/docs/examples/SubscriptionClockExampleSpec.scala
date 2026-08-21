package scalive.docs.examples

import zio.*
import zio.test.*

import scalive.*
import scalive.testing.{ConnectedRender, ConnectedView}

object SubscriptionClockExampleSpec extends ZIOSpecDefault:
  private def eventuallyText(
    harness: ConnectedView[?],
    selector: String,
    expected: String
  ): Task[String] =
    harness.text(selector).repeatUntil(_ == expected)

  override def spec = suite("SubscriptionClockExampleSpec")(
    test("starts, replaces, and cancels one managed clock subscription") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new SubscriptionClockExample("clock-primary"))
          _       <- harness.clickButton("Start every second")
          started <- harness.text("[data-clock-mode]")
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          first   <- harness.text("[data-clock-count]").map(_.toInt)
          _       <- harness.clickButton("Replace with fast clock")
          replaced <- harness.text("[data-clock-mode]")
          _        <- TestClock.adjust(250.millis)
          _        <- harness.awaitDiff
          second   <- harness.text("[data-clock-count]").map(_.toInt)
          _        <- harness.clickButton("Cancel clock")
          cancelled <- harness.text("[data-clock-mode]")
          beforeWait <- harness.text("[data-clock-count]")
          _          <- TestClock.adjust(1.second)
          finalCount <- harness.text("[data-clock-count]")
        yield assertTrue(
          started == "Every second",
          first > 0,
          replaced == "Four times per second",
          second > first,
          cancelled == "Stopped",
          finalCount == beforeWait
        )
      }
    },
    test("reset cancels work and restores initial state") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new SubscriptionClockExample("clock-reset"))
          _       <- harness.clickButton("Start every second")
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          _       <- harness.send(SubscriptionClockExample.Msg.Reset)
          mode    <- eventuallyText(harness, "[data-clock-mode]", "Stopped")
          count   <- harness.text("[data-clock-count]")
          _       <- TestClock.adjust(1.second)
          stable  <- harness.text("[data-clock-count]")
        yield assertTrue(mode == "Stopped", count == "0", stable == "0")
      }
    },
    test("isolates subscription state between instances") {
      ZIO.scoped {
        for
          first  <- ConnectedRender.join(new SubscriptionClockExample("clock-first"))
          second <- ConnectedRender.join(new SubscriptionClockExample("clock-second"))
          _      <- first.clickButton("Start every second")
          firstMode  <- first.text("[data-clock-mode]")
          secondMode <- second.text("[data-clock-mode]")
        yield assertTrue(firstMode == "Every second", secondMode == "Stopped")
      }
    },
    test("derives resource keys from the documentation instance") {
      assertTrue(
        SubscriptionClockExample.subscriptionKey("clock-a").value !=
          SubscriptionClockExample.subscriptionKey("clock-b").value
      )
    }
  )
end SubscriptionClockExampleSpec
