package scalive

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TreeDiffSpec extends ZIOSpecDefault:

  private def asJson(diff: Diff): Json =
    diff.toJsonAST.getOrElse(throw new IllegalArgumentException("Unable to encode diff"))

  private def hasNumericStaticRef(json: Json): Boolean =
    json match
      case Json.Obj(fields) =>
        fields.exists {
          case ("s", Json.Num(_)) => true
          case (_, value)          => hasNumericStaticRef(value)
        }
      case Json.Arr(values) => values.exists(hasNumericStaticRef)
      case _                => false

  private def hasNumericComprehensionStaticRef(json: Json): Boolean =
    json match
      case Json.Obj(fields) =>
        val isComprehension = fields.exists(_._1 == "k")
        val hasStaticRef    = fields.exists {
          case ("s", Json.Num(_)) => true
          case _                   => false
        }
        (isComprehension && hasStaticRef) || fields.exists { case (_, value) =>
          hasNumericComprehensionStaticRef(value)
        }
      case Json.Arr(values) =>
        values.exists(hasNumericComprehensionStaticRef)
      case _ =>
        false

  override def spec = suite("TreeDiffSpec")(
    test("does not emit root-only diffs for unchanged nodes") {
      val previous = div(span("value"))
      val current  = div(span("value"))

      val diff = TreeDiff.diff(previous, current)

      assertTrue(
        diff.isEmpty,
        asJson(diff) == Json.Obj.empty
      )
    },
    test("does not resend unchanged siblings when slot type changes") {
      val title = "Counter with auto increment every second"
      val shown = div(
        h1(title),
        div("1")
      )
      val hidden = div(
        h1(title),
        ""
      )

      val hideDiffJson = TreeDiff.diff(shown, hidden).toJson
      val showDiffJson = TreeDiff.diff(hidden, shown).toJson

      assertTrue(
        !hideDiffJson.contains(title),
        !showDiffJson.contains(title)
      )
    },
    test("emits shared template table for repeated statics") {
      val diff = TreeDiff.initial(
        div(
          span("one"),
          span("two")
        )
      )

      val json = asJson(diff)

      val hasTemplateTable = json match
        case Json.Obj(fields) => fields.exists(_._1 == "p")
        case _                => false

      assertTrue(
        hasTemplateTable,
        hasNumericStaticRef(json)
      )
    },
    test("shares templates for repeated keyed comprehension statics") {
      val diff = TreeDiff.initial(
        div(
          ul(
            List("a", "b").splitBy(identity) { (_, value) =>
              li(value)
            }
          ),
          ul(
            List("c", "d").splitBy(identity) { (_, value) =>
              li(value)
            }
          )
        )
      )

      val json = asJson(diff)

      val hasTemplateTable = json match
        case Json.Obj(fields) => fields.exists(_._1 == "p")
        case _                => false

      assertTrue(
        hasTemplateTable,
        hasNumericComprehensionStaticRef(json)
      )
    },
    test("encodes component refs via c-map and cid slots") {
      val diff = TreeDiff.initial(
        div(
          component(1, span("A")),
          component(2, span("B"))
        )
      )

      val json = asJson(diff)

      val (slot0, slot1, componentMap, hasPositiveStaticCidRef) =
        json match
          case Json.Obj(fields) =>
            val slot0 = fields.collectFirst { case ("0", value) => value }
            val slot1 = fields.collectFirst { case ("1", value) => value }
            val components = fields.collectFirst { case ("c", obj: Json.Obj) => obj }
            val hasPositiveRef =
              components.exists { componentsObj =>
                componentsObj.fields.exists { case (_, value) =>
                  value match
                    case Json.Obj(componentFields) =>
                      componentFields.exists {
                        case ("s", Json.Num(v)) => v.intValue > 0
                        case _                   => false
                      }
                    case _ => false
                }
              }
            (slot0, slot1, components, hasPositiveRef)
          case _                => (None, None, None, false)

      assertTrue(
        slot0.contains(Json.Num(1)),
        slot1.contains(Json.Num(2)),
        componentMap.exists(obj => Set("1", "2").subsetOf(obj.fields.map(_._1).toSet)),
        hasPositiveStaticCidRef
      )
    },
    test("reuses previous component statics with negative cid refs") {
      val previous = div(component(1, span("A")))
      val current  = div(component(3, span("C")))

      val diff = TreeDiff.diff(previous, current)
      val json = asJson(diff)

      val component3StaticRef =
        json match
          case Json.Obj(fields) =>
            fields
              .collectFirst { case ("c", Json.Obj(componentFields)) => componentFields }
              .flatMap(_.collectFirst {
                case ("3", Json.Obj(fields)) =>
                  fields.collectFirst { case ("s", value) => value }
              }.flatten)
          case _                =>
            None

      assertTrue(
        component3StaticRef.contains(Json.Num(-1))
      )
    }
  )
end TreeDiffSpec
