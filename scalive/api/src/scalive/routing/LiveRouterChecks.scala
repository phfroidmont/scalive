package scalive

import scala.quoted.*

/** Compile-time checks behind `Live.router`, which takes routes as varargs and so cannot ask for
  * evidence per route.
  */
private[scalive] object LiveRouterChecks:
  /** Reports, at the offending route, each route that reads context from a named live session. */
  inline def requireRouterRoutes(inline routes: Seq[Any]): Unit = ${
    requireRouterRoutesImpl('routes)
  }

  private def requireRouterRoutesImpl(routes: Expr[Seq[Any]])(using Quotes): Expr[Unit] =
    import quotes.reflect.*

    val input = TypeRepr.of[LiveRegistration[?]].typeSymbol.typeMember("Input")

    def display(tpe: TypeRepr): String =
      if tpe.typeSymbol.fullName.startsWith("scala.Tuple") && tpe.typeArgs.nonEmpty then
        tpe.typeArgs.map(display).mkString("(", ", ", ")")
      else tpe.show(using Printer.TypeReprShortCode)

    def check(tpe: TypeRepr, position: Position): Unit =
      tpe.widen.dealias match
        case OrType(left, right) =>
          check(left, position)
          check(right, position)
        case registration if registration <:< TypeRepr.of[LiveRegistration[Nothing]] =>
          val needed = registration.select(input).dealias.simplified
          if needed.typeSymbol == input then
            report.error(
              "This route's type does not say which session context it reads. Declare it as " +
                "LiveRegistration[R] { type Input = Any } to register it on the router, or " +
                "register it inside the Live.session that produces its context.",
              position
            )
          else if !(needed =:= TypeRepr.of[Any]) then
            val context = display(needed)
            report.error(
              s"This route reads $context from a named live session, so the router cannot " +
                s"mount it. Register it inside a Live.session whose mount aspects produce $context.",
              position
            )
        case _ => ()

    routes match
      case Varargs(arguments) =>
        arguments.foreach(argument => check(argument.asTerm.tpe, argument.asTerm.pos))
      case spliced =>
        val sequence = spliced.asTerm.tpe.widen.baseType(TypeRepr.of[Seq[Any]].typeSymbol)
        sequence.typeArgs.foreach(check(_, spliced.asTerm.pos))
    '{ () }
  end requireRouterRoutesImpl
end LiveRouterChecks
