package scalive

import utest.*
import zio.json.*
import zio.json.ast.Json

object LiveViewSpec extends TestSuite:

  final case class TestModel(
      title: String = "title value",
      bool: Boolean = false,
      nestedTitle: String = "nested title value"
  )

  def assertEqualsJson(actual: Json, expected: Json) =
    assert(actual.toJsonPretty == expected.toJsonPretty)

  val emptyDiff = Json.Obj("diff" -> Json.Obj.empty)

  val tests = Tests {

    test("Static only") {
      val lv =
        LiveViewRenderer.render(
          new LiveView[Unit]:
            val view: HtmlTag[Unit] =
              div("Static string")
          ,
          ()
        )
      test("init") {
        assertEqualsJson(
          lv.buildInitJson,
          Json.Obj(
            "s" -> Json.Arr(Json.Str("<div>Static string</div>"))
          )
        )
      }
      test("diff") {
        assertEqualsJson(lv.buildDiffJson, emptyDiff)
      }
    }

    test("Dynamic string") {
      val lv =
        LiveViewRenderer.render(
          new LiveView[TestModel]:
            val view: HtmlTag[TestModel] =
              div(model(_.title))
          ,
          TestModel()
        )
      test("init") {
        assertEqualsJson(
          lv.buildInitJson,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Str("title value")
            )
        )
      }
      test("diff no update") {
        assertEqualsJson(lv.buildDiffJson, emptyDiff)
      }
      test("diff with update") {
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(
          lv.buildDiffJson,
          Json.Obj(
            "diff" -> Json.Obj("0" -> Json.Str("title updated"))
          )
        )
      }
      test("diff with update and no change") {
        lv.update(TestModel(title = "title updated"))
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(lv.buildDiffJson, emptyDiff)
      }
    }

    test("when mod") {
      val lv =
        LiveViewRenderer.render(
          new LiveView[TestModel]:
            val view: HtmlTag[TestModel] =
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
          lv.buildInitJson,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Bool(false)
            )
        )
      }
      test("diff no update") {
        assertEqualsJson(lv.buildDiffJson, emptyDiff)
      }
      test("diff with unrelated update") {
        lv.update(TestModel(title = "title updated"))
        assertEqualsJson(lv.buildDiffJson, emptyDiff)
      }
      test("diff when true") {
        lv.update(TestModel(bool = true))
        assertEqualsJson(
          lv.buildDiffJson,
          Json.Obj(
            "diff" -> Json.Obj(
              "0" ->
                Json
                  .Obj(
                    "s" -> Json
                      .Arr(Json.Str("<div>static string"), Json.Str("</div>")),
                    "0" -> Json.Str("nested title value")
                  )
            )
          )
        )
      }
      test("diff when nested change") {
        lv.update(TestModel(bool = true))
        lv.update(TestModel(bool = true, nestedTitle = "nested title updated"))
        assertEqualsJson(
          lv.buildDiffJson,
          Json.Obj(
            "diff" -> Json.Obj(
              "0" ->
                Json
                  .Obj(
                    "0" -> Json.Str("nested title updated")
                  )
            )
          )
        )
      }
    }
  }

object TestLiveView extends LiveView[MyModel]:
  val view: HtmlTag[MyModel] =
    div(
      div("Static string 1"),
      model(_.title),
      div("Static string 2"),
      model.when(_.bool)(
        div("maybe rendered", model(_.nestedTitle))
      )
    )
