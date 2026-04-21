package scalive

import zio.json.ast.Json
import zio.json.EncoderOps
import zio.test.*

object LiveViewSpec extends ZIOSpecDefault:

  final case class NestedModel(name: String, age: Int)
  final case class TestModel(
    title: String = "title value",
    subtitle: String = "subtitle value",
    showNested: Boolean = false,
    items: List[NestedModel] = Nil)

  private def baseView(model: TestModel): HtmlElement =
    div(
      h1(model.title),
      p(model.subtitle),
      if model.showNested then span("nested") else ""
    )

  private def keyedView(model: TestModel): HtmlElement =
    div(
      ul(
        model.items.splitBy(_.name) { (_, elem) =>
          li(
            "Nom: ",
            elem.name,
            " Age: ",
            elem.age.toString
          )
        }
      )
    )

  private def asJson(diff: Diff): Json =
    diff.toJsonAST.getOrElse(throw new IllegalArgumentException("Unable to encode diff"))

  private def hasField(json: Json, field: String): Boolean =
    json match
      case Json.Obj(fields) => fields.exists(_._1 == field)
      case _                => false

  override def spec = suite("LiveViewSpec")(
    test("initial render includes static fragments") {
      val json = asJson(TreeDiff.initial(baseView(TestModel())))
      assertTrue(hasField(json, "s"))
    },
    test("unchanged render yields empty diff") {
      val model = TestModel()
      val diff  = TreeDiff.diff(baseView(model), baseView(model))
      assertTrue(diff.isEmpty, asJson(diff) == Json.Obj.empty)
    },
    test("text update yields slot diff") {
      val previous = TestModel(title = "before")
      val current  = TestModel(title = "after")
      val json     = asJson(TreeDiff.diff(baseView(previous), baseView(current)))
      assertTrue(hasField(json, "0"))
    },
    test("conditional branch add and remove") {
      val hidden  = TestModel(showNested = false)
      val visible = TestModel(showNested = true)

      val addJson    = asJson(TreeDiff.diff(baseView(hidden), baseView(visible)))
      val removeJson = asJson(TreeDiff.diff(baseView(visible), baseView(hidden)))

      assertTrue(
        hasField(addJson, "2"),
        hasField(removeJson, "2")
      )
    },
    test("keyed update emits keyed diff payload") {
      val previous = TestModel(items = List(NestedModel("a", 10), NestedModel("b", 20)))
      val current  = TestModel(items = List(NestedModel("a", 11), NestedModel("b", 20)))
      val json     = asJson(TreeDiff.diff(keyedView(previous), keyedView(current)))

      val hasKeyedObject =
        json match
          case Json.Obj(fields) =>
            fields
              .collectFirst { case ("0", Json.Obj(level1)) => level1 }
              .flatMap(_.collectFirst { case ("0", Json.Obj(level2)) => level2 })
              .exists(_.exists(_._1 == "k"))
          case _                =>
            false

      assertTrue(hasKeyedObject)
    }
  )
end LiveViewSpec
