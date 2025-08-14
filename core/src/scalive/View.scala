package scalive

trait View[Model]:
  val model: Dyn[Model, Model] = Dyn.id
  def root: HtmlElement[Model]

opaque type Dyn[I, O] = I => O
extension [I, O](d: Dyn[I, O])
  def apply[O2](f: O => O2): Dyn[I, O2] = d.andThen(f)

  def when(f: O => Boolean)(el: HtmlElement[I]): Mod.When[I] =
    Mod.When(d.andThen(f), el)

  inline def whenNot(f: O => Boolean)(el: HtmlElement[I]): Mod.When[I] =
    when(f.andThen(!_))(el)

  def splitByIndex[O2](f: O => List[O2])(
      project: Dyn[O2, O2] => HtmlElement[O2]
  ): Mod.Split[I, O2] =
    Mod.Split(d.andThen(f), project)

  def run(v: I): O = d(v)

object Dyn:
  def id[T]: Dyn[T, T] = identity

enum Mod[T]:
  case StaticAttr(attr: HtmlAttr, value: String)
  case DynAttr(attr: HtmlAttr, value: Dyn[T, String])
  case Tag(el: HtmlElement[T])
  case Text(text: String)
  case DynText(dynText: Dyn[T, String])
  case When(dynCond: Dyn[T, Boolean], el: HtmlElement[T])
  case Split[T, O](
      dynList: Dyn[T, List[O]],
      project: Dyn[O, O] => HtmlElement[O]
  ) extends Mod[T]

given [T]: Conversion[HtmlElement[T], Mod[T]] = Mod.Tag(_)
given [T]: Conversion[String, Mod[T]] = Mod.Text(_)
given [T]: Conversion[Dyn[T, String], Mod[T]] = Mod.DynText(_)

class HtmlTag(val name: String):
  def apply[Model](mods: Mod[Model]*): HtmlElement[Model] =
    HtmlElement(this, mods.toVector)

class HtmlElement[Model](val tag: HtmlTag, val mods: Vector[Mod[Model]])

val div = HtmlTag("div")
val ul = HtmlTag("ul")
val li = HtmlTag("li")

class HtmlAttr(val name: String):
  def :=[T](value: String): Mod.StaticAttr[T] = Mod.StaticAttr(this, value)
  def :=[T](value: Dyn[T, String]): Mod.DynAttr[T] = Mod.DynAttr(this, value)

val idAttr = HtmlAttr("id")
val cls = HtmlAttr("class")
