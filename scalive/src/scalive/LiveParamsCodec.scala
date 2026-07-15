package scalive

import zio.*
import zio.http.*
import zio.http.codec.{Combiner, HttpCodec, QueryCodec}
import zio.schema.Schema

trait LiveParamsDecoder[PathParams, Params]:
  def decode(
    pathParams: PathParams,
    url: URL
  ): IO[LiveParamsCodec.DecodeError, Params]

  def mapDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveParamsDecoder[PathParams, Params2] =
    val self = this
    new LiveParamsDecoder[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL) =
        self.decode(pathParams, url).map(decodeParams)

trait LiveParamsCodec[PathParams, Params] extends LiveParamsDecoder[PathParams, Params]:
  def encode(
    params: Params
  ): Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[PathParams]]

  def imap[Params2](
    decodeParams: Params => Params2
  )(
    encodeParams: Params2 => Params
  ): LiveParamsCodec[PathParams, Params2] =
    val self = this
    new LiveParamsCodec[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL) =
        self.decode(pathParams, url).map(decodeParams)
      def encode(params: Params2) = self.encode(encodeParams(params))

object LiveParamsDecoder:
  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[LiveParamsCodec.DecodeError | String, Params]
  ): LiveParamsDecoder[PathParams, Params] =
    new LiveParamsDecoder[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL) =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(LiveParamsCodec.normalizeDecodeError))

object LiveParamsCodec:
  final case class Encoded[PathParams](
    pathParams: PathParams,
    queryParams: QueryParams)

  final case class DecodeError(
    message: String,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  def path[A]: LiveParamsCodec[A, A] =
    custom(
      decodeFn = (pathParams, _) => Right(pathParams),
      encodeFn = pathParams => Right(Encoded(pathParams, QueryParams.empty))
    )

  val none: LiveParamsCodec[Unit, Unit] = path[Unit]

  def query[A](using Schema[A]): LiveParamsCodec[Unit, A] =
    fromZioHttp(HttpCodec.query[A])

  def fromZioHttp[A](codec: QueryCodec[A]): LiveParamsCodec[Unit, A] =
    fromQuery(codec)

  def fromQuery[PathParams, QueryParams](
    codec: QueryCodec[QueryParams]
  )(using combiner: Combiner[PathParams, QueryParams]
  ): LiveParamsCodec[PathParams, combiner.Out] =
    new LiveParamsCodec[PathParams, combiner.Out]:
      def decode(pathParams: PathParams, url: URL) =
        codec
          .decodeRequest(Request.get(url))
          .map(queryParams => combiner.combine(pathParams, queryParams))
          .mapError(toDecodeError)

      def encode(params: combiner.Out) =
        try
          val (pathParams, queryParams) = combiner.separate(params)
          Right(Encoded(pathParams, codec.encodeRequest(queryParams).url.queryParams))
        catch
          case scala.util.control.NonFatal(cause) =>
            Left(LiveLocation.EncodeError.Query(cause))

  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[DecodeError | String, Params],
    encodeFn: Params => Either[LiveLocation.EncodeError, Encoded[PathParams]]
  ): LiveParamsCodec[PathParams, Params] =
    new LiveParamsCodec[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL) =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(normalizeDecodeError))
      def encode(params: Params) = encodeFn(params)

  private[scalive] def normalizeDecodeError(error: DecodeError | String): DecodeError =
    error match
      case error: DecodeError => error
      case message: String    => DecodeError(message)

  private def toDecodeError(error: Throwable): DecodeError =
    DecodeError(Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString), Some(error))
end LiveParamsCodec
