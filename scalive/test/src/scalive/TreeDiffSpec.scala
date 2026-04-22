package scalive

import zio.json.*
import zio.json.ast.Json
import zio.test.*

object TreeDiffSpec extends ZIOSpecDefault:

  final case class KeyedRow(id: String, value: String)

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

  private def findComprehensionContainer(json: Json): Option[Json.Obj] =
    json match
      case obj: Json.Obj =>
        if obj.fields.exists(_._1 == "k") then Some(obj)
        else
          obj.fields.iterator
            .map(_._2)
            .collectFirst(Function.unlift(findComprehensionContainer))
      case Json.Arr(values) =>
        values.iterator.collectFirst(Function.unlift(findComprehensionContainer))
      case _ =>
        None

  private def keyedFields(json: Json): Map[String, Json] =
    findComprehensionContainer(json)
      .flatMap(_.fields.collectFirst { case ("k", keyed: Json.Obj) => keyed.fields.toMap })
      .getOrElse(Map.empty)

  private def topLevelComponents(json: Json): Map[String, Json] =
    json match
      case Json.Obj(fields) =>
        fields
          .collectFirst { case ("c", components: Json.Obj) => components.fields.toMap }
          .getOrElse(Map.empty)
      case _ =>
        Map.empty

  private def staticField(value: Json): Option[Json] =
    value match
      case Json.Obj(fields) => fields.collectFirst { case ("s", staticValue) => staticValue }
      case _                => None

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
    test("keyed subtree unchanged yields empty diff via fingerprints") {
      val previous = ul(
        List("a", "b").splitBy(identity) { (_, value) =>
          li(value)
        }
      )
      val current = ul(
        List("a", "b").splitBy(identity) { (_, value) =>
          li(value)
        }
      )

      val diff = TreeDiff.diff(previous, current)

      assertTrue(
        diff.isEmpty,
        asJson(diff) == Json.Obj.empty
      )
    },
    test("keyed reorder emits index changes without entry payloads") {
      val previous = ul(
        List("a", "b", "c").splitBy(identity) { (_, value) =>
          li(value)
        }
      )
      val current = ul(
        List("b", "a", "c").splitBy(identity) { (_, value) =>
          li(value)
        }
      )

      val keyed = keyedFields(asJson(TreeDiff.diff(previous, current)))

      assertTrue(
        keyed.get("0").contains(Json.Num(1)),
        keyed.get("1").contains(Json.Num(0)),
        keyed.get("kc").contains(Json.Num(3))
      )
    },
    test("keyed reorder with changed entry emits index merge payload") {
      val previous = ul(
        List(KeyedRow("a", "A"), KeyedRow("b", "B")).splitBy(_.id) { (_, row) =>
          li(row.value)
        }
      )
      val current = ul(
        List(KeyedRow("b", "B2"), KeyedRow("a", "A")).splitBy(_.id) { (_, row) =>
          li(row.value)
        }
      )

      val keyed = keyedFields(asJson(TreeDiff.diff(previous, current)))

      val hasIndexMerge = keyed.get("0").exists {
        case Json.Arr(values) =>
          values.headOption.contains(Json.Num(1)) &&
          values.lift(1).exists {
            case Json.Obj(fields) => fields.exists(_._1 == "0")
            case _                => false
          }
        case _ =>
          false
      }

      assertTrue(
        hasIndexMerge,
        keyed.get("1").contains(Json.Num(0)),
        keyed.get("kc").contains(Json.Num(2))
      )
    },
    test("keyed deletion can emit count-only kc updates") {
      val previous = ul(
        List("a", "b", "c").splitBy(identity) { (_, value) =>
          li(value)
        }
      )
      val current = ul(
        List("a", "b").splitBy(identity) { (_, value) =>
          li(value)
        }
      )

      val keyed         = keyedFields(asJson(TreeDiff.diff(previous, current)))
      val nonCountField = keyed.keySet.filterNot(_ == "kc")

      assertTrue(
        keyed.get("kc").contains(Json.Num(2)),
        nonCountField.isEmpty
      )
    },
    test("stream patches emit keyed stream payload with full entries") {
      val entries = Vector(
        Mod.Content.Keyed.Entry("users-1", li("one"))
      )

      val previous = ul(Mod.Content.Keyed(entries))
      val current = ul(
        Mod.Content.Keyed(
          entries = entries,
          stream = Some(
            Diff.Stream(
              ref = "0",
              inserts = Vector(
                Diff.StreamInsert(
                  domId = "users-1",
                  at = -1,
                  limit = None,
                  updateOnly = None
                )
              ),
              deleteIds = Vector.empty,
              reset = false
            )
          )
        )
      )

      val json              = asJson(TreeDiff.diff(previous, current))
      val keyed             = keyedFields(json)
      val comprehensionNode = findComprehensionContainer(json)

      val hasStreamRef = comprehensionNode.exists { node =>
        node.fields
          .collectFirst { case ("stream", Json.Arr(values)) => values }
          .exists(_.headOption.contains(Json.Str("0")))
      }

      assertTrue(
        hasStreamRef,
        keyed.contains("0"),
        keyed.get("kc").contains(Json.Num(1))
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
    test("does not emit template table when statics are not repeated") {
      val diff = TreeDiff.initial(
        div(
          h1("one"),
          p("two"),
          em("three")
        )
      )

      val json = asJson(diff)

      val hasTemplateTable = json match
        case Json.Obj(fields) => fields.exists(_._1 == "p")
        case _                => false

      assertTrue(
        !hasTemplateTable,
        !hasNumericStaticRef(json)
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
    test("component diff map omits unchanged components") {
      val previous = div(
        component(1, span("A")),
        component(2, span("B"))
      )
      val current = div(
        component(1, span("A")),
        component(2, span("C"))
      )

      val components = topLevelComponents(asJson(TreeDiff.diff(previous, current)))

      val hasComponent2TextUpdate = components.get("2").exists {
        case Json.Obj(fields) =>
          fields.collectFirst { case ("0", Json.Str(value)) => value }.contains("C")
        case _ =>
          false
      }

      assertTrue(
        components.keySet == Set("2"),
        hasComponent2TextUpdate
      )
    },
    test("component static sharing is deterministic by component id") {
      val diff = TreeDiff.initial(
        div(
          component(2, span("second")),
          component(1, span("first"))
        )
      )

      val components = topLevelComponents(asJson(diff))
      val component1 = components.get("1").flatMap(staticField)
      val component2 = components.get("2").flatMap(staticField)

      assertTrue(
        component1.exists {
          case Json.Arr(_) => true
          case _           => false
        },
        component2.contains(Json.Num(1))
      )
    },
    test("component static sharing falls back to inline statics on template mismatch") {
      val previous = div(component(1, span("A")))
      val current  = div(component(2, div("B")))

      val components = topLevelComponents(asJson(TreeDiff.diff(previous, current)))
      val component2 = components.get("2").flatMap(staticField)

      assertTrue(
        component2.exists {
          case Json.Arr(_) => true
          case _           => false
        }
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
