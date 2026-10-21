package scalive

import zio.test.*

object NavigationGuardSpec extends ZIOSpecDefault:
  private def evaluate[A](signal: Signal[A], sourceValue: Boolean): A =
    signal.expression match
      case Signal.Expression.Source(_) => sourceValue.asInstanceOf[A]
      case Signal.Expression.Mapped(parent, project) => project(evaluate(parent, sourceValue))
      case Signal.Expression.Zipped(left, right) =>
        (evaluate(left, sourceValue), evaluate(right, sourceValue)).asInstanceOf[A]
      case Signal.Expression.Constant(value) => value

  override def spec = suite("NavigationGuardSpec")(
    test("renders the guard message only while dirty") {
      val dirty   = Signal.source[Boolean](new Object)
      val message = "Unsaved changes"
      val guard   = navigation.guardWhen(dirty, message)

      guard match
        case Mod.Attr.SignalOptionalValue(name, value) =>
          assertTrue(
            name == "data-scalive-navigation-guard",
            evaluate(value, sourceValue = true).contains(message),
            evaluate(value, sourceValue = false).isEmpty
          )
        case _ => assertTrue(false)
    },
    test("guards patches only for the any-navigation scope") {
      val dirty   = Signal.source[Boolean](new Object)
      val leaving = navigation.guardWhen(dirty, "Unsaved", NavigationGuardScope.LeavingView)
      val any     = navigation.guardWhen(dirty, "Unsaved", NavigationGuardScope.AnyNavigation)
      val names   = (guard: Mod.Attr[Nothing]) =>
        guard.flattened.map {
          case Mod.Attr.SignalOptionalValue(name, _) => name
          case Mod.Attr.Static(name, _)              => name
          case other                                 => other.toString
        }

      assertTrue(
        names(navigation.guardWhen(dirty, "Unsaved")) == names(leaving),
        names(leaving) == Vector("data-scalive-navigation-guard"),
        names(any) == Vector(
          "data-scalive-navigation-guard",
          "data-scalive-navigation-guard-patches"
        )
      )
    },
    test("rejects blank messages") {
      val dirty = Signal.source[Boolean](new Object)

      assertTrue(
        scala.util.Try(navigation.guardWhen(dirty, " \t\n ")).isFailure,
        scala.util.Try(navigation.guardWhen(dirty, "\u2003")).isFailure
      )
    },
    test("preserves message text for HTML escaping at render time") {
      val dirty   = Signal.source[Boolean](new Object)
      val message = "Leave <now> & say \"goodbye\""
      val guard   = navigation.guardWhen(dirty, message)

      guard match
        case Mod.Attr.SignalOptionalValue(_, value) =>
          assertTrue(
            evaluate(value, sourceValue = true).contains(message),
            Escaping.escape(message) == "Leave &lt;now&gt; &amp; say &quot;goodbye&quot;"
          )
        case _ => assertTrue(false)
    }
  )
end NavigationGuardSpec
