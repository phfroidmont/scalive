package scalive.docs.examples

import org.jsoup.Jsoup
import scala.jdk.CollectionConverters.*
import zio.*
import zio.test.*

import scalive.testing.ConnectedRender

object ComponentSubscriptionsExampleSpec extends ZIOSpecDefault:
  override def spec = suite("ComponentSubscriptionsExampleSpec")(
    test("mounts two independently controlled subscriptions with the same local key") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new ComponentSubscriptionsExample)
          initial <- harness.html.map(Jsoup.parseBodyFragment)
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          _       <- harness.awaitDiff
          ticking <- harness.html.map(Jsoup.parseBodyFragment)
          firstBeforeCancel = ticking
                                .select("[data-subscription-component=first-ticker] [data-component-ticks]")
                                .text().toInt
          secondBeforeCancel = ticking
                                 .select("[data-subscription-component=second-ticker] [data-component-ticks]")
                                 .text().toInt
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:last-of-type"
               )
          cancelled <- harness.text(
                         "[data-subscription-component=first-ticker] [data-component-mode]"
                       )
          _ <- TestClock.adjust(1.second)
          _ <- harness.awaitDiff
          after <- harness.html.map(Jsoup.parseBodyFragment)
          firstAfter = after
                         .select("[data-subscription-component=first-ticker] [data-component-ticks]")
                         .text().toInt
          secondAfter = after
                          .select("[data-subscription-component=second-ticker] [data-component-ticks]")
                          .text().toInt
        yield assertTrue(
          initial.select("[data-component-mode]").eachText().asScala.toVector ==
            Vector("Every second", "Every second"),
          firstBeforeCancel > 0,
          secondBeforeCancel > 0,
          cancelled == "Stopped",
          firstAfter == firstBeforeCancel,
          secondAfter > secondBeforeCancel
        )
      }
    },
    test("replaces and restarts a component-local stream through local controls") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new ComponentSubscriptionsExample)
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:last-of-type"
               )
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:nth-of-type(2)"
               )
          replaced <- harness.text(
                         "[data-subscription-component=first-ticker] [data-component-mode]"
                       )
          _ <- TestClock.adjust(250.millis)
          _ <- harness.awaitDiff
          fastCount <- harness
                         .text("[data-subscription-component=first-ticker] [data-component-ticks]")
                         .map(_.toInt)
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:last-of-type"
               )
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:first-of-type"
               )
          restarted <- harness.text(
                         "[data-subscription-component=first-ticker] [data-component-mode]"
                       )
          _ <- TestClock.adjust(250.millis)
          beforeSlowTick <- harness
                              .text("[data-subscription-component=first-ticker] [data-component-ticks]")
                              .map(_.toInt)
          _ <- TestClock.adjust(750.millis)
          slowCount <- harness
                         .text("[data-subscription-component=first-ticker] [data-component-ticks]")
                         .map(_.toInt).repeatUntil(_ > fastCount)
        yield assertTrue(
          replaced == "Four times per second",
          restarted == "Every second",
          fastCount == 1,
          beforeSlowTick == fastCount,
          slowCount == 2
        )
      }
    },
    test("reset clears local models and replaces cancelled subscriptions") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new ComponentSubscriptionsExample)
          _ <- harness.click(
                 "[data-subscription-component=first-ticker] button:last-of-type"
               )
          _      <- TestClock.adjust(1.second)
          _      <- harness.awaitDiff
          beforeReset <- harness.html.map(Jsoup.parseBodyFragment)
          _      <- harness.send(ComponentSubscriptionsExample.Msg.Reset)
          reset  <- harness.html.map(Jsoup.parseBodyFragment)
          _      <- TestClock.adjust(1.second)
          _      <- harness.awaitDiff
          _      <- harness.awaitDiff
          resumed <- harness.html.map(Jsoup.parseBodyFragment)
        yield assertTrue(
          beforeReset
            .select("[data-subscription-component=first-ticker] [data-component-mode]").text() ==
            "Stopped",
          beforeReset
            .select("[data-subscription-component=second-ticker] [data-component-ticks]").text()
            .toInt > 0,
          reset.select("[data-component-mode]").eachText().asScala.toVector ==
            Vector("Every second", "Every second"),
          reset.select("[data-component-ticks]").eachText().asScala.toVector == Vector("0", "0"),
          resumed.select("[data-component-ticks]").eachText().asScala.forall(_.toInt > 0)
        )
      }
    },
    test("reinsert before browser destruction retains the model and restarts its subscription") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new ComponentSubscriptionsExample)
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          _       <- harness.awaitDiff
          before <- harness
                      .text("[data-subscription-component=first-ticker] [data-component-ticks]")
                      .map(_.toInt)
          _      <- harness.clickButton("Remove first ticker")
          absent <- harness.text("[data-first-visibility]")
          _      <- harness.clickButton("Reinsert first ticker")
          after  <- harness.html.map(Jsoup.parseBodyFragment)
          retained = after
                       .select("[data-subscription-component=first-ticker] [data-component-ticks]")
                       .text().toInt
          _       <- TestClock.adjust(1.second)
          _       <- harness.awaitDiff
          _       <- harness.awaitDiff
          resumed <- harness
                       .text("[data-subscription-component=first-ticker] [data-component-ticks]")
                       .map(_.toInt)
        yield assertTrue(
          before > 0,
          absent == "First ticker is removed.",
          retained >= before,
          resumed > retained,
          after.select("[data-subscription-component=first-ticker] [data-component-mode]").text() ==
            "Every second"
        )
      }
    }
  ) @@ TestAspect.timeout(30.seconds)
end ComponentSubscriptionsExampleSpec
