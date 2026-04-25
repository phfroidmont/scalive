package scalive

private[scalive] enum BindingPayload:
  case Params(values: Map[String, String])
  case Form(data: FormData)

  def params: Map[String, String] =
    this match
      case Params(values) => values
      case Form(data)     => data.asMap

  def formData: FormData =
    this match
      case Params(values) => FormData.fromMap(values)
      case Form(data)     => data

final private[scalive] class BindingHandler[Msg](run: BindingPayload => Either[String, Msg]):
  def apply(params: Map[String, String]): Either[String, Msg] =
    run(BindingPayload.Params(params))

  def apply(payload: BindingPayload): Either[String, Msg] =
    run(payload)
