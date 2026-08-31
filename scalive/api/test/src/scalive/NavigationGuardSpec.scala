package scalive

import zio.test.*

object NavigationGuardSpec extends ZIOSpecDefault:
  private def evaluate[A](signal: Signal[A], sourceValue: Boolean): A =
    signal.expression match
      case Signal.Expression.Source(_) => sourceValue.asInstanceOf[A]
      case Signal.Expression.Mapped(parent, project) => project(evaluate(parent, sourceValue))
      case Signal.Expression.Zipped(left, right) =>
        (evaluate(left, sourceValue), evaluate(right, sourceValue)).asInstanceOf[A]

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
