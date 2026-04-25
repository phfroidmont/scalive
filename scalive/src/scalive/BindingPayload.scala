package scalive

private[scalive] enum BindingPayload:
  case Params(values: Map[String, String])
  case Form(data: FormData, meta: FormEvent.Meta = FormEvent.Meta.empty)

  def params: Map[String, String] =
    this match
      case Params(values)   => values
      case Form(data, meta) =>
        val params = data.asMap ++ meta.params
        meta.submitter match
          case Some(FormSubmitter(name, value)) if !params.contains(name) => params.updated(name, value)
          case _                                                          => params

  def formData: FormData =
    this match
      case Params(values) => FormData.fromMap(values)
      case Form(data, _)  => data

  def formEvent[A](codec: FormCodec[A], submitted: Boolean): FormEvent[A] =
    this match
      case Params(values) =>
        FormEvent.decode(FormData.fromMap(values), codec, submitted, FormEvent.Meta.empty)
      case Form(data, meta) =>
        FormEvent.decode(data, codec, submitted, meta)

final private[scalive] class BindingHandler[Msg](run: BindingPayload => Either[String, Msg]):
  def apply(params: Map[String, String]): Either[String, Msg] =
    run(BindingPayload.Params(params))

  def apply(payload: BindingPayload): Either[String, Msg] =
    run(payload)
