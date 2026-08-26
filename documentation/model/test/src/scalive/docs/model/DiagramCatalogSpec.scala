package scalive.docs.model

import zio.json.*
import zio.test.*

object DiagramCatalogSpec extends ZIOSpecDefault:
  override def spec = suite("DiagramCatalogSpec")(
    test("defines a valid catalog with lookup by id") {
      assertTrue(
        DiagramCatalog.validate().isEmpty,
        DiagramCatalog.entries.forall(diagram => DiagramCatalog.get(diagram.id).contains(diagram)),
        DiagramCatalog.get("missing").isEmpty
      )
    },
    test("round trips comparison layout metadata") {
      val diagram = DiagramCatalog.RuntimeOwnership
      val encoded = diagram.toJson
      assertTrue(encoded.fromJson[DiagramDefinition] == Right(diagram))
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
