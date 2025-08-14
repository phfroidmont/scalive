package scalive

import utest.*
import zio.json.*
import zio.json.ast.Json

object LiveViewSpec extends TestSuite:

  final case class TestModel(
      title: String = "title value",
      bool: Boolean = false,
      nestedTitle: String = "nested title value",
      items: List[NestedModel] = List.empty
  )
  final case class NestedModel(name: String, age: Int)

  def assertEqualsJson(actual: Diff, expected: Json) =
    assert(actual.toJsonPretty == expected.toJsonPretty)

  val emptyDiff = Json.Obj.empty

  val tests = Tests {

    test("Static only") {
      val lv =
        LiveView(
          new View[Unit]:
            val root: HtmlElement[Unit] =
              div("Static string")
          ,
          ()
        )
      test("init") {
        assertEqualsJson(
          lv.fullDiff,
          Json.Obj(
            "s" -> Json.Arr(Json.Str("<div>Static string</div>"))
          )
        )
      }
      test("diff") {
        assertEqualsJson(lv.diff, emptyDiff)
      }
    }

    test("Dynamic string") {
      val lv =
        LiveView(
          new View[TestModel]:
            val root: HtmlElement[TestModel] =
              div(model(_.title))
          ,
          TestModel()
        )
      test("init") {
        assertEqualsJson(
          lv.fullDiff,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Str("title value")
            )
        )
      }
      test("diff no update") {
        assertEqualsJson(lv.diff, emptyDiff)
      }
      test("diff with update") {
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(
          lv.diff,
          Json.Obj("0" -> Json.Str("title updated"))
        )
      }
      test("diff with update and no change") {
        lv.update(TestModel(title = "title updated"))
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(lv.diff, emptyDiff)
      }
    }

    test("when mod") {
      val lv =
        LiveView(
          new View[TestModel]:
            val root: HtmlElement[TestModel] =
              div(
                model.when(_.bool)(
                  div("static string", model(_.nestedTitle))
                )
              )
          ,
          TestModel()
        )
      test("init") {
        assertEqualsJson(
          lv.fullDiff,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Bool(false)
            )
        )
      }
      test("diff no update") {
        assertEqualsJson(lv.diff, emptyDiff)
      }
      test("diff with unrelated update") {
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(lv.diff, emptyDiff)
      }
      test("diff when true") {
        lv.update(TestModel(bool = true))
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "s" -> Json
                    .Arr(Json.Str("<div>static string"), Json.Str("</div>")),
                  "0" -> Json.Str("nested title value")
                )
          )
        )
      }
      test("diff when nested change") {
        lv.update(TestModel(bool = true))
        lv.update(TestModel(bool = true, nestedTitle = "nested title updated"))
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "0" -> Json.Str("nested title updated")
                )
          )
        )
      }
    }

    test("splitByIndex mod") {
      val initModel =
        TestModel(
          items = List(
            NestedModel("a", 10),
            NestedModel("b", 15),
            NestedModel("c", 20)
          )
        )
      val lv =
        LiveView(
          new View[TestModel]:
            val root: HtmlElement[TestModel] =
              div(
                ul(
                  model.splitByIndex(_.items)(elem =>
                    li(
                      "Nom: ",
                      elem(_.name),
                      " Age: ",
                      elem(_.age.toString)
                    )
                  )
                )
              )
          ,
          initModel
        )
      test("init") {
        assertEqualsJson(
          lv.fullDiff,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div><ul>"), Json.Str("</ul></div>")),
              "0" -> Json.Obj(
                "s" -> Json.Arr(
                  Json.Str("<li>Nom: "),
                  Json.Str(" Age: "),
                  Json.Str("</li>")
                ),
                "d" -> Json.Obj(
                  "0" -> Json.Obj(
                    "0" -> Json.Str("a"),
                    "1" -> Json.Str("10")
                  ),
                  "1" -> Json.Obj(
                    "0" -> Json.Str("b"),
                    "1" -> Json.Str("15")
                  ),
                  "2" -> Json.Obj(
                    "0" -> Json.Str("c"),
                    "1" -> Json.Str("20")
                  )
                )
              )
            )
        )
      }
      test("diff no update") {
        assertEqualsJson(lv.diff, emptyDiff)
      }
      test("diff with unrelated update") {
        lv.update(initModel.copy(title = "title updated"))
        assertEqualsJson(lv.diff, emptyDiff)
      }
      test("diff with item changed") {
        lv.update(
          initModel.copy(items =
            initModel.items.updated(2, NestedModel("c", 99))
          )
        )
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "d" -> Json.Obj(
                    "2" -> Json.Obj(
                      "1" -> Json.Str("99")
                    )
                  )
                )
          )
        )
      }
      test("diff with item added") {
        lv.update(
          initModel.copy(items = initModel.items.appended(NestedModel("d", 35)))
        )
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "d" -> Json.Obj(
                    "3" -> Json.Obj(
                      "0" -> Json.Str("d"),
                      "1" -> Json.Str("35")
                    )
                  )
                )
          )
        )
      }
      test("diff with first item removed") {
        lv.update(
          initModel.copy(items = initModel.items.tail)
        )
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "d" -> Json.Obj(
                    "0" -> Json.Obj(
                      "0" -> Json.Str("b"),
                      "1" -> Json.Str("15")
                    ),
                    "1" -> Json.Obj(
                      "0" -> Json.Str("c"),
                      "1" -> Json.Str("20")
                    ),
                    "2" -> Json.Bool(false)
                  )
                )
          )
        )
      }
      test("diff all removed") {
        lv.update(initModel.copy(items = List.empty))
        assertEqualsJson(
          lv.diff,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "d" -> Json.Obj(
                    "0" -> Json.Bool(false),
                    "1" -> Json.Bool(false),
                    "2" -> Json.Bool(false)
                  )
                )
          )
        )

      }
    }
  }
