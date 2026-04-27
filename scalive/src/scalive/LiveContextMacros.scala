package scalive

import scala.quoted.*

import zio.*

private[scalive] object LiveContextMacros:
  def assignAsyncImpl[Model: Type, A: Type](
    model: Expr[Model],
    mode: Expr[AsyncStartMode],
    reset: Expr[Boolean],
    field: Expr[Model => AsyncValue[A]],
    effect: Expr[Task[A]]
  )(using Quotes
  ): Expr[URIO[LiveContext.HasAsync, Model]] =
    val fieldName = selectedFieldName(field)
    val setter    = setterExpr[Model, A](fieldName)
    val keyName   = asyncKeyName[Model](fieldName)

    '{
      val setAsyncField = $setter
      val loadingModel  = setAsyncField(
        $model,
        AsyncValue.markLoading($field($model), $reset)
      )

      ZIO
        .serviceWithZIO[LiveContext.HasAsync](
          _.async.startAssign[A, Model](${ Expr(keyName) }, $mode)($effect) {
            (currentModel, result) =>
              setAsyncField(currentModel, AsyncValue.applyResult($field(currentModel), result))
          }
        ).as(loadingModel)
    }

  def cancelAssignAsyncImpl[Model: Type, A: Type](
    model: Expr[Model],
    field: Expr[Model => AsyncValue[A]],
    reason: Expr[Option[String]]
  )(using Quotes
  ): Expr[URIO[LiveContext.HasAsync, Unit]] =
    val _         = model
    val fieldName = selectedFieldName(field)
    val keyName   = asyncKeyName[Model](fieldName)

    '{ LiveContext.cancelAsync(${ Expr(keyName) }, $reason) }

  private def asyncKeyName[Model: Type](fieldName: String)(using Quotes): String =
    s"${Type.show[Model]}.$fieldName"

  private def selectedFieldName[Model: Type, A: Type](
    field: Expr[Model => AsyncValue[A]]
  )(using Quotes
  ): String =
    import quotes.reflect.*

    def strip(term: Term): Term =
      term match
        case Inlined(_, _, inner) => strip(inner)
        case Typed(inner, _)      => strip(inner)
        case Block(Nil, inner)    => strip(inner)
        case other                => other

    def lambdaParts(term: Term): Option[(Symbol, Term)] =
      strip(term) match
        case Block(List(defDef: DefDef), Closure(_, _)) =>
          val param = defDef.paramss.collectFirst { case TermParamClause(param :: Nil) =>
            param.symbol
          }
          param.flatMap(symbol => defDef.rhs.map(rhs => symbol -> rhs))
        case _ => None

    def isParamRef(term: Term, param: Symbol): Boolean =
      strip(term) match
        case ref: Ref => ref.symbol == param
        case _        => false

    lambdaParts(field.asTerm) match
      case Some((param, body)) =>
        strip(body) match
          case Select(qualifier, name) if isParamRef(qualifier, param) => name
          case other                                                   =>
            report.errorAndAbort(
              s"assignAsync field must be a direct AsyncValue field selector like _.user, got: ${other.show}"
            )
      case None =>
        report.errorAndAbort(
          "assignAsync field must be a direct AsyncValue field selector like _.user"
        )
  end selectedFieldName

  private def setterExpr[Model: Type, A: Type](
    fieldName: String
  )(using Quotes
  ): Expr[(Model, AsyncValue[A]) => Model] =
    import quotes.reflect.*

    val modelType  = TypeRepr.of[Model]
    val caseFields = modelType.typeSymbol.caseFields
    if !caseFields.exists(_.name == fieldName) then
      report.errorAndAbort(
        s"assignAsync can only update case class fields; ${Type.show[Model]} has no case field '$fieldName'"
      )

    val methodType = MethodType(List("model", "value"))(
      _ => List(TypeRepr.of[Model], TypeRepr.of[AsyncValue[A]]),
      _ => TypeRepr.of[Model]
    )

    Lambda(
      Symbol.spliceOwner,
      methodType,
      (_, params) =>
        val model = params.head.asInstanceOf[Term]
        val value = params(1).asInstanceOf[Term]
        val args  = caseFields.map { field =>
          val fieldValue =
            if field.name == fieldName then value
            else Select.unique(model, field.name)
          NamedArg(field.name, fieldValue)
        }
        Apply(Select.unique(model, "copy"), args)
    ).asExprOf[(Model, AsyncValue[A]) => Model]
  end setterExpr
end LiveContextMacros
