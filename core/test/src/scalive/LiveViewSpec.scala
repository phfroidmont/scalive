package scalive

import utest.*
import zio.json.*
import zio.json.ast.Json

object LiveViewSpec extends TestSuite:

  final case class TestModel(
    title: String = "title value",
    otherString: String = "other string value",
    bool: Boolean = false,
    nestedTitle: String = "nested title value",
    cls: String = "text-sm",
    items: List[NestedModel] = List.empty)
  final case class NestedModel(name: String, age: Int)
  final case class UpdateCmd(f: TestModel => TestModel)

  def assertEqualsDiff(el: HtmlElement, expected: Json, trackChanges: Boolean = true) =
    el.syncAll()
    val actual = DiffBuilder.build(el, trackUpdates = trackChanges)
    assert(actual.toJsonPretty == expected.toJsonPretty)

  val emptyDiff = Json.Obj.empty

  val tests = Tests {

    test("Static only") {
      val lv =
        new LiveView[Unit]:
          val el                             = div("Static string")
          def handleCommand(cmd: Unit): Unit = ()
      lv.el.syncAll()

      test("init") {
        assertEqualsDiff(
          lv.el,
          Json.Obj(
            "s" -> Json.Arr(Json.Str("<div>Static string</div>"))
          ),
          trackChanges = false
        )
      }
      test("diff") {
        assertEqualsDiff(lv.el, emptyDiff)
      }
    }

    test("Dynamic string") {
      val lv =
        new LiveView[UpdateCmd]:
          val model = Var(TestModel())
          val el    =
            div(
              h1(model(_.title)),
              p(model(_.otherString))
            )
          def handleCommand(cmd: UpdateCmd): Unit = model.update(cmd.f)

      lv.el.syncAll()
      lv.el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          lv.el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div><h1>"), Json.Str("</h1><p>"), Json.Str("</p></div>")),
              "0" -> Json.Str("title value"),
              "1" -> Json.Str("other string value")
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with update") {
        lv.handleCommand(UpdateCmd(_.copy(title = "title updated")))
        assertEqualsDiff(
          lv.el,
          Json.Obj("0" -> Json.Str("title updated"))
        )
      }
      test("diff with update and no change") {
        lv.handleCommand(UpdateCmd(_.copy(title = "title value")))
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with update in multiple commands") {
        lv.handleCommand(UpdateCmd(_.copy(title = "title updated")))
        lv.handleCommand(UpdateCmd(_.copy(otherString = "other string updated")))
        assertEqualsDiff(
          lv.el,
          Json
            .Obj(
              "0" -> Json.Str("title updated"),
              "1" -> Json.Str("other string updated")
            )
        )
      }
    }

    test("Dynamic attribute") {
      val lv =
        new LiveView[UpdateCmd]:
          val model = Var(TestModel())
          val el    =
            div(cls := model(_.cls))
          def handleCommand(cmd: UpdateCmd): Unit = model.update(cmd.f)

      lv.el.syncAll()
      lv.el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          lv.el,
          Json
            .Obj(
              "s" -> Json
                .Arr(Json.Str("<div class=\""), Json.Str("\"></div>")),
              "0" -> Json.Str("text-sm")
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with update") {
        lv.handleCommand(UpdateCmd(_.copy(cls = "text-md")))
        assertEqualsDiff(
          lv.el,
          Json.Obj("0" -> Json.Str("text-md"))
        )
      }
    }

    test("when mod") {
      val lv =
        new LiveView[UpdateCmd]:
          val model = Var(TestModel())
          val el    =
            div(
              model.when(_.bool)(
                div("static string", model(_.nestedTitle))
              )
            )
          def handleCommand(cmd: UpdateCmd): Unit = model.update(cmd.f)

      lv.el.syncAll()
      lv.el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          lv.el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Bool(false)
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with unrelated update") {
        lv.handleCommand(UpdateCmd(_.copy(title = "title updated")))
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff when true and nested update") {
        lv.handleCommand(UpdateCmd(_.copy(bool = true)))
        assertEqualsDiff(
          lv.el,
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
        lv.handleCommand(UpdateCmd(_.copy(bool = true)))
        lv.el.syncAll()
        lv.el.setAllUnchanged()
        lv.handleCommand(UpdateCmd(_.copy(bool = true, nestedTitle = "nested title updated")))
        assertEqualsDiff(
          lv.el,
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
      val initModel = TestModel(
        items = List(
          NestedModel("a", 10),
          NestedModel("b", 15),
          NestedModel("c", 20)
        )
      )
      val lv =
        new LiveView[UpdateCmd]:
          val model = Var(initModel)
          val el    =
            div(
              ul(
                model(_.items).splitByIndex((_, elem) =>
                  li(
                    "Nom: ",
                    elem(_.name),
                    " Age: ",
                    elem(_.age.toString)
                  )
                )
              )
            )
          def handleCommand(cmd: UpdateCmd): Unit = model.update(cmd.f)

      lv.el.syncAll()
      lv.el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          lv.el,
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
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with unrelated update") {
        lv.handleCommand(UpdateCmd(_.copy(title = "title updated")))
        assertEqualsDiff(lv.el, emptyDiff)
      }
      test("diff with item changed") {
        lv.handleCommand(
          UpdateCmd(_.copy(items = initModel.items.updated(2, NestedModel("c", 99))))
        )
        assertEqualsDiff(
          lv.el,
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
        lv.handleCommand(
          UpdateCmd(
            _.copy(items = initModel.items.appended(NestedModel("d", 35)))
          )
        )
        assertEqualsDiff(
          lv.el,
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
        lv.handleCommand(
          UpdateCmd(
            _.copy(items = initModel.items.tail)
          )
        )
        assertEqualsDiff(
          lv.el,
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
        lv.handleCommand(UpdateCmd(_.copy(items = List.empty)))
        assertEqualsDiff(
          lv.el,
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
end LiveViewSpec
