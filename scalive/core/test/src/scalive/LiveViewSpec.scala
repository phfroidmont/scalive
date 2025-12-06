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

  def assertEqualsDiff(el: HtmlElement, expected: Json, trackChanges: Boolean = true) =
    el.syncAll()
    val actual = DiffBuilder.build(el, trackUpdates = trackChanges)
    assert(actual.toJsonPretty == expected.toJsonPretty)

  val emptyDiff = Json.Obj.empty

  val tests = Tests {

    test("Static only") {
      val el = div("Static string")
      el.syncAll()

      test("init") {
        assertEqualsDiff(
          el,
          Json.Obj(
            "s" -> Json.Arr(Json.Str("<div>Static string</div>"))
          ),
          trackChanges = false
        )
      }
      test("diff") {
        assertEqualsDiff(el, emptyDiff)
      }
    }

    test("Dynamic string") {
      val model = Var(TestModel())
      val el    =
        div(
          h1(model(_.title)),
          p(model(_.otherString))
        )

      el.syncAll()
      el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          el,
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
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with update") {
        model.update(_.copy(title = "title updated"))
        assertEqualsDiff(
          el,
          Json.Obj("0" -> Json.Str("title updated"))
        )
      }
      test("diff with update and no change") {
        model.update(_.copy(title = "title value"))
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with update in multiple commands") {
        model.update(_.copy(title = "title updated"))
        model.update(_.copy(otherString = "other string updated"))
        assertEqualsDiff(
          el,
          Json
            .Obj(
              "0" -> Json.Str("title updated"),
              "1" -> Json.Str("other string updated")
            )
        )
      }
    }

    test("Dynamic attribute") {
      val model = Var(TestModel())
      val el    =
        div(cls := model(_.cls))

      el.syncAll()
      el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          el,
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
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with update") {
        model.update(_.copy(cls = "text-md"))
        assertEqualsDiff(
          el,
          Json.Obj("0" -> Json.Str("text-md"))
        )
      }
    }

    test("when mod") {
      val model = Var(TestModel())
      val el    =
        div(
          model.when(_.bool)(
            div("static string", model(_.nestedTitle))
          )
        )

      el.syncAll()
      el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div>"), Json.Str("</div>")),
              "0" -> Json.Str("")
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with unrelated update") {
        model.update(_.copy(title = "title updated"))
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff when true and nested update") {
        model.update(_.copy(bool = true))
        assertEqualsDiff(
          el,
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
        model.update(_.copy(bool = true))
        el.syncAll()
        el.setAllUnchanged()
        model.update(_.copy(bool = true, nestedTitle = "nested title updated"))
        assertEqualsDiff(
          el,
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

      el.syncAll()
      el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div><ul>"), Json.Str("</ul></div>")),
              "0" -> Json.Obj(
                "s" -> Json.Arr(
                  Json.Str("<li>Nom: "),
                  Json.Str(" Age: "),
                  Json.Str("</li>")
                ),
                "k" -> Json.Obj(
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
                  ),
                  "kc" -> Json.Num(3)
                )
              )
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with unrelated update") {
        model.update(_.copy(title = "title updated"))
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with item changed") {
        model.update(_.copy(items = initModel.items.updated(2, NestedModel("c", 99))))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "2" -> Json.Obj(
                      "1" -> Json.Str("99")
                    ),
                    "kc" -> Json.Num(3)
                  )
                )
          )
        )
      }
      test("diff with item added") {
        model.update(_.copy(items = initModel.items.appended(NestedModel("d", 35))))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "3" -> Json.Obj(
                      "0" -> Json.Str("d"),
                      "1" -> Json.Str("35")
                    ),
                    "kc" -> Json.Num(4)
                  )
                )
          )
        )
      }
      test("diff add one to empty list") {
        val model = Var(TestModel(items = List.empty))
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
        el.syncAll()
        el.setAllUnchanged()

        model.update(_.copy(items = List(NestedModel("a", 20))))

        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "s" -> Json.Arr(
                    Json.Str("<li>Nom: "),
                    Json.Str(" Age: "),
                    Json.Str("</li>")
                  ),
                  "k" -> Json.Obj(
                    "0" -> Json.Obj(
                      "0" -> Json.Str("a"),
                      "1" -> Json.Str("20")
                    ),
                    "kc" -> Json.Num(1)
                  )
                )
          )
        )
      }
      test("diff with first item removed") {
        model.update(_.copy(items = initModel.items.tail))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "0" -> Json.Obj(
                      "0" -> Json.Str("b"),
                      "1" -> Json.Str("15")
                    ),
                    "1" -> Json.Obj(
                      "0" -> Json.Str("c"),
                      "1" -> Json.Str("20")
                    ),
                    "kc" -> Json.Num(2)
                  )
                )
          )
        )
      }
      test("diff all removed") {
        model.update(_.copy(items = List.empty))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "kc" -> Json.Num(0)
                  )
                )
          )
        )
      }
    }

    test("splitById mod") {
      val initModel = TestModel(
        items = List(
          NestedModel("a", 10),
          NestedModel("b", 15),
          NestedModel("c", 20)
        )
      )
      val model = Var(initModel)
      val el    =
        div(
          ul(
            model(_.items).splitBy(_.name)((_, elem) =>
              li(
                "Nom: ",
                elem(_.name),
                " Age: ",
                elem(_.age.toString)
              )
            )
          )
        )

      el.syncAll()
      el.setAllUnchanged()

      test("init") {
        assertEqualsDiff(
          el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div><ul>"), Json.Str("</ul></div>")),
              "0" -> Json.Obj(
                "s" -> Json.Arr(
                  Json.Str("<li>Nom: "),
                  Json.Str(" Age: "),
                  Json.Str("</li>")
                ),
                "k" -> Json.Obj(
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
                  ),
                  "kc" -> Json.Num(3)
                )
              )
            ),
          trackChanges = false
        )
      }
      test("diff no update") {
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with unrelated update") {
        model.update(_.copy(title = "title updated"))
        assertEqualsDiff(el, emptyDiff)
      }
      test("diff with item changed") {
        model.update(_.copy(items = initModel.items.updated(2, NestedModel("c", 99))))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "2" -> Json.Obj(
                      "1" -> Json.Str("99")
                    ),
                    "kc" -> Json.Num(3)
                  )
                )
          )
        )
      }
      test("diff with item added") {
        model.update(_.copy(items = initModel.items.appended(NestedModel("d", 35))))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "3" -> Json.Obj(
                      "0" -> Json.Str("d"),
                      "1" -> Json.Str("35")
                    ),
                    "kc" -> Json.Num(4)
                  )
                )
          )
        )
      }
      test("diff add one to empty list") {
        val model = Var(TestModel(items = List.empty))
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
        el.syncAll()
        el.setAllUnchanged()

        model.update(_.copy(items = List(NestedModel("a", 20))))

        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "s" -> Json.Arr(
                    Json.Str("<li>Nom: "),
                    Json.Str(" Age: "),
                    Json.Str("</li>")
                  ),
                  "k" -> Json.Obj(
                    "0" -> Json.Obj(
                      "0" -> Json.Str("a"),
                      "1" -> Json.Str("20")
                    ),
                    "kc" -> Json.Num(1)
                  )
                )
          )
        )
      }
      test("diff with first item removed") {
        model.update(_.copy(items = initModel.items.tail))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "0"  -> Json.Num(1),
                    "1"  -> Json.Num(2),
                    "kc" -> Json.Num(2)
                  )
                )
          )
        )
      }
      test("diff all removed") {
        model.update(_.copy(items = List.empty))
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "kc" -> Json.Num(0)
                  )
                )
          )
        )
      }
    }

    test("splitBy ordering") {
      val initModel = TestModel(
        items = List(
          NestedModel("c", 20),
          NestedModel("a", 10),
          NestedModel("b", 15)
        )
      )
      val model = Var(initModel)
      val el    =
        div(
          ul(
            model(_.items).splitBy(_.name)((_, elem) =>
              li(
                "Nom: ",
                elem(_.name),
                " Age: ",
                elem(_.age.toString)
              )
            )
          )
        )

      el.syncAll()
      el.setAllUnchanged()

      test("init should preserve list order") {
        assertEqualsDiff(
          el,
          Json
            .Obj(
              "s" -> Json.Arr(Json.Str("<div><ul>"), Json.Str("</ul></div>")),
              "0" -> Json.Obj(
                "s" -> Json.Arr(
                  Json.Str("<li>Nom: "),
                  Json.Str(" Age: "),
                  Json.Str("</li>")
                ),
                "k" -> Json.Obj(
                  "0" -> Json.Obj(
                    "0" -> Json.Str("c"),
                    "1" -> Json.Str("20")
                  ),
                  "1" -> Json.Obj(
                    "0" -> Json.Str("a"),
                    "1" -> Json.Str("10")
                  ),
                  "2" -> Json.Obj(
                    "0" -> Json.Str("b"),
                    "1" -> Json.Str("15")
                  ),
                  "kc" -> Json.Num(3)
                )
              )
            ),
          trackChanges = false
        )
      }

      test("reorder items") {
        model.update(
          _.copy(items =
            List(
              NestedModel("b", 15), // Should be at index 0
              NestedModel("a", 10), // Should be at index 1
              NestedModel("c", 20)  // Should be at index 2
            )
          )
        )
        assertEqualsDiff(
          el,
          Json.Obj(
            "0" ->
              Json
                .Obj(
                  "k" -> Json.Obj(
                    "0"  -> Json.Num(2),
                    "2"  -> Json.Num(0),
                    "kc" -> Json.Num(3)
                  )
                )
          )
        )
      }
    }

  }
end LiveViewSpec
