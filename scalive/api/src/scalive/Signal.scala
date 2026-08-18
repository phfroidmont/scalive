package scalive

/** A read-only value sampled by the render engine. */
sealed trait Signal[+A]:
  private[scalive] def expression: Signal.Expression[A]

  /** Derives a signal with a pure transformation. */
  final def map[B](f: A => B): Signal[B] = Signal.Mapped(this, f)

  /** Combines two signals. Scope compatibility is validated by the render engine. */
  final def zip[B](that: Signal[B]): Signal[(A, B)] = Signal.Zipped(this, that)

object Signal:
  /** Opaque source identity installed by the render engine. */
  private[scalive] enum Expression[+A]:
    case Source[A](identity: Object)                     extends Expression[A]
    case Mapped[A, B](parent: Signal[A], f: A => B)      extends Expression[B]
    case Zipped[A, B](left: Signal[A], right: Signal[B]) extends Expression[(A, B)]

  final private class Source[A](identity: Object) extends Signal[A]:
    private[scalive] val expression: Expression[A] = Expression.Source(identity)

  final private case class Mapped[A, B](parent: Signal[A], f: A => B) extends Signal[B]:
    private[scalive] val expression: Expression[B] = Expression.Mapped(parent, f)

  final private case class Zipped[A, B](left: Signal[A], right: Signal[B]) extends Signal[(A, B)]:
    private[scalive] val expression: Expression[(A, B)] = Expression.Zipped(left, right)

  private[scalive] def source[A](identity: Object): Signal[A] = Source(identity)

  extension (condition: Signal[Boolean])
    def when[Msg](content: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> content))

    def choose[Msg](whenTrue: => HtmlElement[Msg], whenFalse: => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

    def chooseMod[Msg](whenTrue: => Mod[Msg], whenFalse: => Mod[Msg]): Mod[Msg] =
      Mod.Content.SignalModChoice(condition, Vector(true -> whenTrue, false -> whenFalse))

  extension [A](value: Signal[Option[A]])
    def option[Msg](project: Signal[A] => HtmlElement[Msg]): Mod[Msg] =
      Mod.Content.SignalOption(value, project)

  extension [A](value: Signal[A])
    def choose[Msg](branches: (A, HtmlElement[Msg])*): Mod[Msg] =
      Mod.Content.SignalChoice(value, branches.toVector)

    def chooseMod[Msg](branches: (A, Mod[Msg])*): Mod[Msg] =
      Mod.Content.SignalModChoice(value, branches.toVector)
end Signal
