package scalive

trait LiveView[Model]:
  val model: Dyn[Model, Model] = Dyn.id
  def view: HtmlTag[Model]

opaque type Dyn[I, O] = I => O
extension [I, O](d: Dyn[I, O])
  def apply[O2](f: O => O2): Dyn[I, O2] = d.andThen(f)

  def when(f: O => Boolean)(tag: HtmlTag[I]): Mod.When[I] =
    Mod.When(d.andThen(f), tag)

  inline def whenNot(f: O => Boolean)(tag: HtmlTag[I]): Mod.When[I] =
    when(f.andThen(!_))(tag)

  def splitByIndex[O2](f: O => List[O2])(
      project: Dyn[O2, O2] => HtmlTag[O2]
  ): Mod.Split[I, O2] =
    Mod.Split(d.andThen(f), project)

  def run(v: I): O = d(v)

object Dyn:
  def id[T]: Dyn[T, T] = identity

enum Mod[T]:
  case Tag(tag: HtmlTag[T])
  case Text(text: String)
  case DynText(dynText: Dyn[T, String])
  case When(dynCond: Dyn[T, Boolean], tag: HtmlTag[T])
  case Split[T, O](
      dynList: Dyn[T, List[O]],
      project: Dyn[O, O] => HtmlTag[O]
  ) extends Mod[T]

given [T]: Conversion[HtmlTag[T], Mod[T]] = Mod.Tag(_)
given [T]: Conversion[String, Mod[T]] = Mod.Text(_)
given [T]: Conversion[Dyn[T, String], Mod[T]] = Mod.DynText(_)

trait HtmlTag[Model](val name: String):
  def mods: List[Mod[Model]]

class Div[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]("div")
class Ul[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]("ul")
class Li[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]("li")

def div[Model](mods: Mod[Model]*): Div[Model] = Div(mods.toList)
def ul[Model](mods: Mod[Model]*): Ul[Model] = Ul(mods.toList)
def li[Model](mods: Mod[Model]*): Li[Model] = Li(mods.toList)
