import scala.collection.immutable.ArraySeq

@main
def main =
  val temlate = Template(TestLiveView.render)
  println(temlate.init(MyModel("Initial title")))
  println(temlate.update(MyModel("Updated title")))

trait LiveView[Model]:
  val model = Dyn[Model, Model](identity)
  def render: HtmlTag[Model]

final case class MyModel(title: String)

object TestLiveView extends LiveView[MyModel]:
  def render: HtmlTag[MyModel] =
    div(
      div("some text"),
      model(_.title)
    )

class Dyn[I, O](f: I => O):
  private var last: Option[O] = None

  def apply[O2](f2: O => O2): Dyn[I, O2] = Dyn(f.andThen(f2))

  def forceUpate(v: I): O =
    val newValue = f(v)
    last = Some(newValue)
    newValue

  def update(v: I): Option[O] =
    val newValue = f(v)
    last match
      case Some(lastValue) if lastValue == newValue => None
      case _ =>
        last = Some(newValue)
        last

enum Mod[T]:
  case Tag(v: HtmlTag[T])
  case Text(v: String)
  case DynText(v: Dyn[T, String])

given [T]: Conversion[HtmlTag[T], Mod[T]] = Mod.Tag(_)
given [T]: Conversion[String, Mod[T]] = Mod.Text(_)
given [T]: Conversion[Dyn[T, String], Mod[T]] = Mod.DynText(_)

class Template[Model](
    private val static: ArraySeq[String],
    private val dynamic: ArraySeq[Dyn[Model, String]]
):
  def init(model: Model): Template.InitialState =
    Template.InitialState(
      static,
      dynamic.map(_.forceUpate(model))
    )
  def update(model: Model): Template.Diff =
    Template.Diff(
      dynamic.zipWithIndex.flatMap((dyn, i) => dyn.update(model).map(i -> _))
    )
object Template:
  final case class InitialState(static: Seq[String], dynamic: Seq[String])
  final case class Diff(dynamic: Seq[(Int, String)])
  def apply[Model](tag: HtmlTag[Model]) =
    val (static, dynamic) = buildTag(tag)
    new Template(static.to(ArraySeq), dynamic.to(ArraySeq))

def buildMod[Model](mod: Mod[Model]): (List[String], List[Dyn[Model, String]]) =
  mod match
    case Mod.Tag(v)            => buildTag(v)
    case Mod.Text(v)           => (List(v), List.empty)
    case Mod.DynText[Model](v) => (List.empty, List(v))

def buildTag[Model](
    tag: HtmlTag[Model]
): (List[String], List[Dyn[Model, String]]) =
  val modsBuilt: List[(List[String], List[Dyn[Model, String]])] =
    tag.mods.map(buildMod)
  val static =
    List(s"<${tag.name}>") ++
      modsBuilt.flatMap(_._1) ++
      List(s"</${tag.name}>")
  val dynamic = modsBuilt.flatMap(_._2)
  (static, dynamic)

trait HtmlTag[Model]:
  def name: String
  def mods: List[Mod[Model]]

class Div[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]:
  val name = "div"

def div[Model](mods: Mod[Model]*): Div[Model] = Div(mods.toList)
