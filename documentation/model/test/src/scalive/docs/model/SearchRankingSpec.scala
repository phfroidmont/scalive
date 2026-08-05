package scalive.docs.model

import java.nio.charset.StandardCharsets
import scala.io.Source
import scala.util.Using

import zio.json.*
import zio.test.*

object SearchRankingSpec extends ZIOSpecDefault:
  private final case class RankingCase(query: String, limit: Int, ids: Vector[String])
      derives JsonDecoder

  private final case class RankingFixture(
    entries: Vector[SearchEntry],
    cases: Vector[RankingCase])
      derives JsonDecoder

  private val fixture =
    val resource = Option(getClass.getClassLoader.getResource("search-ranking.json"))
      .getOrElse(throw new IllegalStateException("Search ranking fixture is missing"))
    val json = Using.resource(Source.fromURL(resource, StandardCharsets.UTF_8.name()))(_.mkString)
    json.fromJson[RankingFixture].fold(error => throw new IllegalArgumentException(error), identity)

  override def spec = suite("SearchRankingSpec")(
    test("matches the shared deterministic ranking contract") {
      val failures = fixture.cases.flatMap { rankingCase =>
        val actual = SearchRanking
          .search(rankingCase.query, fixture.entries, rankingCase.limit)
          .map(_.id)
        Option.when(actual != rankingCase.ids)(
          s"${rankingCase.query}: expected ${rankingCase.ids.mkString(",")}, got ${actual.mkString(",")}"
        )
      }

      assertTrue(failures.isEmpty)
    },
    test("preserves Scala identifiers and derives dotted and camel-case aliases") {
      assertTrue(
        SearchRanking.terms("scalive.LiveView") == Vector("scalive.liveview"),
        SearchRanking.terms("LiveView handleMessage") == Vector("liveview", "handlemessage"),
        SearchRanking.aliases("scalive.LiveView.handleMessage").contains("scalive.liveview.handlemessage"),
        SearchRanking.aliases("scalive.LiveView.handleMessage").contains("liveview"),
        SearchRanking.aliases("scalive.LiveView.handleMessage").contains("handle"),
        SearchRanking.aliases("scalive.LiveView.handleMessage").contains("message")
      )
    }
  )
end SearchRankingSpec
