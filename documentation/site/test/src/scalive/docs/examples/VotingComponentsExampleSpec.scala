package scalive.docs.examples

import org.jsoup.Jsoup
import zio.*
import zio.test.*

import scalive.testing.{ConnectedRender, ConnectedView}

object VotingComponentsExampleSpec extends ZIOSpecDefault:
  private def state(harness: ConnectedView[?]) =
    harness.html.map(Jsoup.parseBodyFragment)

  private def waitFor(harness: ConnectedView[?], text: String) =
    harness.html.repeatUntil(_.contains(text))

  override def spec = suite("VotingComponentsExampleSpec")(
    test("isolates component state and reports typed outputs to the parent") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new VotingComponentsExample)
          _       <- harness.click("[data-vote-component=scala-vote] button:first-of-type")
          _       <- waitFor(harness, "scala-vote reported 1 vote.")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-vote-component=scala-vote] [data-vote-count]").text() == "1",
          current.select("[data-vote-component=zio-vote] [data-vote-count]").text() == "0",
          current.select("[data-vote-status]").text() == "scala-vote reported 1 vote."
        )
      }
    },
    test("updates props without losing component-local state") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new VotingComponentsExample)
          _       <- harness.click("[data-vote-component=scala-vote] button:first-of-type")
          _       <- waitFor(harness, "scala-vote reported 1 vote.")
          _       <- harness.clickButton("Parent updates Scala props")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-vote-component=scala-vote] h4").text() == "Scala language",
          current.select("[data-vote-component=scala-vote] [data-props-revision]").text() ==
            "props r1",
          current.select("[data-vote-component=scala-vote] [data-vote-count]").text() == "1"
        )
      }
    },
    test("shows stable identities and reports local reset to the parent") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new VotingComponentsExample)
          initial <- state(harness)
          _       <- harness.click("[data-vote-component=zio-vote] button:first-of-type")
          _       <- waitFor(harness, "zio-vote reported 1 vote.")
          _       <- harness.click("[data-vote-component=zio-vote] button:last-of-type")
          _       <- waitFor(harness, "zio-vote reported 0 votes.")
          current <- state(harness)
        yield assertTrue(
          initial.select("[data-component-id=scala-vote]").text() == "scala-vote",
          initial.select("[data-component-id=zio-vote]").text() == "zio-vote",
          initial.select("[data-vote-label]").eachText().toString == "[Votes, Votes]",
          current.select("[data-vote-component=zio-vote] [data-vote-count]").text() == "0",
          current.select("[data-vote-status]").text() == "zio-vote reported 0 votes."
        )
      }
    },
    test("resets parent and component state explicitly") {
      ZIO.scoped {
        for
          harness <- ConnectedRender.join(new VotingComponentsExample)
          _       <- harness.click("[data-vote-component=zio-vote] button:first-of-type")
          _       <- waitFor(harness, "zio-vote reported 1 vote.")
          _       <- harness.send(VotingComponentsExample.Msg.Reset)
          _       <- waitFor(harness, "No component has reported a vote.")
          current <- state(harness)
        yield assertTrue(
          current.select("[data-vote-count]").eachText().toString == "[0, 0]",
          current.select("[data-vote-status]").text() == "No component has reported a vote."
        )
      }
    }
  )
end VotingComponentsExampleSpec
