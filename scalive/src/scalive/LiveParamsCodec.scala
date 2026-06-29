package scalive

import zio.*
import zio.http.*
import zio.http.codec.{HttpCodec, QueryCodec}
import zio.schema.Schema

trait LiveParamsCodec[PathParams, Params]:
  type Out = Params

  def decode(pathParams: PathParams, url: URL): IO[LiveParamsCodec.DecodeError, Params]

  def map[Params2](
    decodeParams: Params => Params2
  ): LiveParamsCodec[PathParams, Params2] =
    val self = this
    new LiveParamsCodec[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL): IO[LiveParamsCodec.DecodeError, Params2] =
        self.decode(pathParams, url).map(decodeParams)

object LiveParamsCodec:
  final case class DecodeError(
    message: String,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  def path[A]: LiveParamsCodec[A, A] =
    custom(
      decodeFn = (pathParams, _) => Right(pathParams)
    )

  val none: LiveParamsCodec[Unit, Unit] = path[Unit]

  def query[A](using Schema[A]): LiveParamsCodec[Unit, A] =
    fromZioHttp(HttpCodec.query[A])

  def fromZioHttp[A](codec: QueryCodec[A]): LiveParamsCodec[Unit, A] =
    fromQuery(codec)

  def fromQuery[PathParams, QueryParams](
    codec: QueryCodec[QueryParams]
  )(using combiner: zio.http.codec.Combiner[PathParams, QueryParams]
  ): LiveParamsCodec[PathParams, combiner.Out] =
    new LiveParamsCodec[PathParams, combiner.Out]:
      def decode(
        pathParams: PathParams,
        url: URL
      ): IO[LiveParamsCodec.DecodeError, combiner.Out] =
        codec
          .decodeRequest(Request.get(url))
          .map(queryParams => combiner.combine(pathParams, queryParams))
          .mapError(toDecodeError)

  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[DecodeError | String, Params]
  ): LiveParamsCodec[PathParams, Params] =
    new LiveParamsCodec[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL): IO[DecodeError, Params] =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(normalizeDecodeError))

  private def normalizeDecodeError(error: DecodeError | String): DecodeError =
    error match
      case error: DecodeError => error
      case message: String    => DecodeError(message)

  private def toDecodeError(error: Throwable): DecodeError =
    DecodeError(Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString), Some(error))
end LiveParamsCodec
