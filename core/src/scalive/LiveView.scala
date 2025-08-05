package scalive

trait LiveView[Model]:
  val model: Dyn[Model, Model] = Dyn.id
  def view: HtmlTag[Model]

opaque type Dyn[I, O] = I => O
extension [I, O](d: Dyn[I, O])
  def apply[O2](f: O => O2): Dyn[I, O2] = d.andThen(f)
  def when(f: O => Boolean)(tag: HtmlTag[I]) = Mod.When(d.andThen(f), tag)
  def run(v: I): O = d(v)
object Dyn:
  def id[T]: Dyn[T, T] = identity

enum Mod[T]:
  case Tag(tag: HtmlTag[T])
  case Text(text: String)
  case DynText(dynText: Dyn[T, String])
  case When(dynCond: Dyn[T, Boolean], tag: HtmlTag[T])

given [T]: Conversion[HtmlTag[T], Mod[T]] = Mod.Tag(_)
given [T]: Conversion[String, Mod[T]] = Mod.Text(_)
given [T]: Conversion[Dyn[T, String], Mod[T]] = Mod.DynText(_)

trait HtmlTag[Model]:
  def name: String
  def mods: List[Mod[Model]]

class Div[Model](val mods: List[Mod[Model]]) extends HtmlTag[Model]:
  val name = "div"

def div[Model](mods: Mod[Model]*): Div[Model] = Div(mods.toList)
