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
        DiagramCatalog.prose(DiagramCatalog.RuntimeConnectedTurn).contains(
          "write failure does not roll back N+1"
        )
      )
    },
    test("round trips catalog metadata with stable display-size JSON") {
      val diagram = DiagramCatalog.RuntimeOwnership
      val encoded = diagram.toJson
      assertTrue(
        encoded.fromJson[DiagramDefinition] == Right(diagram),
        encoded.contains("\"displaySize\":\"wide\""),
        encoded.contains("\"width\":1490"),
        encoded.contains("\"height\":813")
      )
    },
    test("reports invalid catalog metadata") {
      val invalid = DiagramCatalog.RuntimeOwnership.copy(
        id = "Bad_ID",
        assetFilename = "runtime.png",
        caption = " ",
        description = "",
        intrinsicSize = DiagramIntrinsicSize(0, -1)
      )
      assertTrue(
        DiagramCatalog.validate(Vector(invalid)) == Vector(
          "diagram 'Bad_ID' asset filename must be a lowercase kebab-case SVG filename.",
          "diagram 'Bad_ID' caption must not be blank.",
          "diagram 'Bad_ID' description must not be blank.",
          "diagram 'Bad_ID' intrinsic dimensions must be positive.",
          "invalid diagram id 'Bad_ID'; expected lowercase kebab-case."
        )
      )
    }
  )
