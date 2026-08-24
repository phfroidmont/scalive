package scalive.docs.model

import zio.json.*
import zio.test.*

object DiagramCatalogSpec extends ZIOSpecDefault:
  override def spec = suite("DiagramCatalogSpec")(
    test("defines the two valid runtime diagrams with searchable prose") {
      assertTrue(
        DiagramCatalog.entries.map(_.id) == Vector(
          "runtime-ownership",
          "runtime-connected-turn"
        ),
        DiagramCatalog.validate().isEmpty,
        DiagramCatalog.get("runtime-ownership").contains(DiagramCatalog.RuntimeOwnership),
        DiagramCatalog.RuntimeOwnership.assets.map(_.filename) == Vector(
          "runtime-disconnected-lifetime.svg",
          "runtime-connected-lifetime.svg"
        ),
        DiagramCatalog.prose(DiagramCatalog.RuntimeConnectedTurn).contains(
          "write failure does not roll back N+1"
        ),
        DiagramCatalog.RuntimeConnectedTurn.assets.map(_.intrinsicSize) == Vector(
          DiagramIntrinsicSize(width = 520, height = 1250)
        )
      )
    },
    test("round trips comparison layout metadata") {
      val diagram = DiagramCatalog.RuntimeOwnership
      val encoded = diagram.toJson
      assertTrue(
        encoded.fromJson[DiagramDefinition] == Right(diagram),
        encoded.contains("runtime-disconnected-lifetime.svg"),
        encoded.contains("runtime-connected-lifetime.svg"),
        encoded.contains("\"width\":480"),
        encoded.contains("\"height\":760")
      )
    },
    test("reports invalid catalog metadata") {
      val invalid = DiagramCatalog.RuntimeOwnership.copy(
        id = "Bad_ID",
        caption = " ",
        description = "",
        layout = DiagramLayout.Single(
          DiagramAsset(
            label = " ",
            filename = "runtime.png",
            intrinsicSize = DiagramIntrinsicSize(0, -1)
          )
        )
      )
      assertTrue(
        DiagramCatalog.validate(Vector(invalid)) == Vector(
          "diagram 'Bad_ID' asset filename must be a lowercase kebab-case SVG filename.",
          "diagram 'Bad_ID' asset label must not be blank.",
          "diagram 'Bad_ID' caption must not be blank.",
          "diagram 'Bad_ID' description must not be blank.",
          "diagram 'Bad_ID' intrinsic dimensions must be positive.",
          "invalid diagram id 'Bad_ID'; expected lowercase kebab-case."
        )
      )
    }
  )
