import scala.collection.immutable.ArraySeq
import zio.json.*
import zio.json.ast.*
import scala.collection.mutable.ListBuffer

@main
def main =
  val r = TestLiveView.render(MyModel("Initial title"))
  println(JsonWriter.toJson(r.buildClientStateInit))
  r.update(MyModel("Updated title"))
  println(JsonWriter.toJson(r.buildClientStateDiff))
  r.update(MyModel("Updated title"))
  println(JsonWriter.toJson(r.buildClientStateDiff))

trait LiveView[Model]:
  val model: Dyn[Model, Model] = Dyn.id
  def view: HtmlTag[Model]

final case class MyModel(title: String)

object TestLiveView extends LiveView[MyModel]:
  val view: HtmlTag[MyModel] =
    div(
      div("before"),
      model(_.title),
      div("after")
    )

object JsonWriter:
  def toJson(init: ClientState.Init): String =
    Json
      .Obj("s" -> Json.Arr(init.static.map(Json.Str(_))*))
      .merge(
        Json.Obj(
          init.dynamic.zipWithIndex.map((v, i) => i.toString -> Json.Str(v))*
        )
      )
      .toJsonPretty
  def toJson(diff: ClientState.Diff): String =
    Json
      .Obj(
        "diff" ->
          Json.Obj(
            diff.dynamic.map((i, v) => i.toString -> Json.Str(v))*
          )
      )
      .toJsonPretty

class RenderedDyn[I, O](d: Dyn[I, O], init: I):
  private var value: O = d.run(init)
  private var updated: Boolean = false

  def wasUpdated: Boolean = updated
  def currentValue: O = value

  def update(v: I): Unit =
    val newValue = d.run(v)
    if value == newValue then updated = false
    else
      value = newValue
      updated = true

opaque type Dyn[I, O] = I => O
extension [I, O](d: Dyn[I, O])
  def apply[O2](f: O => O2): Dyn[I, O2] = d.andThen(f)
  def run(v: I): O = d(v)
object Dyn:
  def id[T]: Dyn[T, T] = identity

enum Mod[T]:
  case Tag(v: HtmlTag[T])
  case Text(v: String)
  case DynText(v: Dyn[T, String])

given [T]: Conversion[HtmlTag[T], Mod[T]] = Mod.Tag(_)
given [T]: Conversion[String, Mod[T]] = Mod.Text(_)
given [T]: Conversion[Dyn[T, String], Mod[T]] = Mod.DynText(_)

object ClientState:
  final case class Init(static: Seq[String], dynamic: Seq[String])
  final case class Diff(dynamic: Seq[(Int, String)])

extension [Model](lv: LiveView[Model])
  def render(model: Model): RenderedLiveView[Model] =
    RenderedLiveView(lv.view, model)

class RenderedLiveView[Model] private (
    private val static: ArraySeq[String],
    private val dynamic: ArraySeq[
      RenderedDyn[Model, String] // | RenderedLiveView[Model]
    ]
):
  def update(model: Model): Unit =
    dynamic.foreach(_.update(model))

  def buildClientStateInit: ClientState.Init =
    ClientState.Init(
      static,
      dynamic.map(_.currentValue)
    )
  def buildClientStateDiff: ClientState.Diff =
    ClientState.Diff(
      dynamic.zipWithIndex.collect {
        case (dyn, i) if dyn.wasUpdated => i -> dyn.currentValue
      }
    )

object RenderedLiveView:
  def apply[Model](tag: HtmlTag[Model], model: Model) =
    val static = ListBuffer.empty[String]
    val dynamic = ListBuffer.empty[RenderedDyn[Model, String]]

    var staticFragment = ""
    for elem <- buildTag(tag, model) do
      elem match
        case s: String =>
          staticFragment += s
        case d: Dyn[Model, String] =>
          static.append(staticFragment)
          staticFragment = ""
          dynamic.append(RenderedDyn(d, model))
    if staticFragment.nonEmpty then static.append(staticFragment)
    new RenderedLiveView(static.to(ArraySeq), dynamic.to(ArraySeq))

  private def buildTag[Model](
      tag: HtmlTag[Model],
      model: Model
  ): List[String | Dyn[Model, String]] =
    (s"<${tag.name}>"
      :: tag.mods.flatMap(buildMod(_, model))) :+
      (s"</${tag.name}>")

  private def buildMod[Model](
      mod: Mod[Model],
      model: Model
  ): List[String | Dyn[Model, String]] =
    mod match
      case Mod.Tag(v)            => buildTag(v, model)
      case Mod.Text(v)           => List(v)
      case Mod.DynText[Model](v) => List(v)

trait HtmlTag[Model]:
  def name: String
  def mods: List[Mod[Model]]

class Div[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]:
  val name = "div"

def div[Model](mods: Mod[Model]*): Div[Model] = Div(mods.toList)
