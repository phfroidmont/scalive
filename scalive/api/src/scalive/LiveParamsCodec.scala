package scalive

import zio.*
import zio.http.*
import zio.http.codec.{Combiner, HttpCodec, QueryCodec}
import zio.schema.Schema

/** Decodes a route's path values and complete URL into parameters for a routed LiveView.
  *
  * A decoder is intentionally one-way. Routes built with it can consume URLs, but cannot construct
  * a [[LiveLocation]] from `Params`; use [[LiveParamsCodec]] when both operations are required.
  *
  * @tparam PathParams
  *   the values decoded by the route's path codec
  * @tparam Params
  *   the parameters supplied to [[LiveView.Routed]]
  */
trait LiveParamsDecoder[PathParams, Params]:
  /** Decodes parameters for the current URL.
    *
    * @param pathParams
    *   the already-decoded path values
    * @param url
    *   the complete current URL, including its query and fragment
    * @return
    *   an effect that succeeds with the routed parameters or fails with a normalized decode error
    */
  def decode(
    pathParams: PathParams,
    url: URL
  ): IO[LiveParamsCodec.DecodeError, Params]

  /** Maps successfully decoded parameters without defining an inverse mapping.
    *
    * The result remains decode-only even when this decoder was originally a [[LiveParamsCodec]].
    * This is useful for validation or normalization that cannot be reversed, and deliberately
    * removes typed location construction from the resulting route builder.
    *
    * @param decodeParams
    *   transforms the decoded value; exceptions thrown by this function are defects, not
    *   [[LiveParamsCodec.DecodeError]] values
    * @return
    *   a decoder for the transformed parameter type
    */
  def mapDecodeOnly[Params2](
    decodeParams: Params => Params2
  ): LiveParamsDecoder[PathParams, Params2] =
    val self = this
    new LiveParamsDecoder[PathParams, Params2]:
      def decode(pathParams: PathParams, url: URL) =
        self.decode(pathParams, url).map(decodeParams)
end LiveParamsDecoder

/** Bidirectionally maps route path/query values and typed routed parameters.
  *
  * Decoding supplies `Params` to [[LiveView.Routed]]. Encoding produces the path and query values
  * used to construct a checked [[LiveLocation]], so codecs should obey a round-trip law for every
  * parameter value accepted by the application.
  *
  * @tparam PathParams
  *   the values decoded and encoded by the route's path codec
  * @tparam Params
  *   the application-facing routed parameter type
  */
trait LiveParamsCodec[PathParams, Params] extends LiveParamsDecoder[PathParams, Params]:
  /** Encodes typed parameters into the route's path and query portions.
    *
    * @param params
    *   the parameters to encode
    * @return
    *   encoded path/query values, or a location encoding error
    */
  def encode(
    params: Params
  ): Either[LiveLocation.EncodeError, LiveParamsCodec.Encoded[PathParams]]

  /** Invariantly maps this codec to an application-facing parameter type.
    *
    * @param decodeParams
    *   maps decoded base parameters to the new type; thrown exceptions become effect defects
    * @param encodeParams
    *   maps the new type back before encoding; thrown exceptions escape the checked encoding API
    * @return
    *   a bidirectional codec for `Params2`
    */
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
end LiveParamsCodec

/** Factories for decode-only routed parameters. */
object LiveParamsDecoder:
  /** Creates a decoder from an application function.
    *
    * Returning a string is shorthand for `LiveParamsCodec.DecodeError(message)`. The function is
    * expected to be total: an exception thrown while invoking it is not converted into a decode
    * error.
    *
    * @param decodeFn
    *   decodes the path values and complete URL
    * @return
    *   a decoder that normalizes string failures
    */
  def custom[PathParams, Params](
    decodeFn: (PathParams, URL) => Either[LiveParamsCodec.DecodeError | String, Params]
  ): LiveParamsDecoder[PathParams, Params] =
    new LiveParamsDecoder[PathParams, Params]:
      def decode(pathParams: PathParams, url: URL) =
        ZIO.fromEither(decodeFn(pathParams, url).left.map(LiveParamsCodec.normalizeDecodeError))

/** Standard codecs, encoded values, and errors for typed Live route parameters. */
object LiveParamsCodec:
  /** The URL portions produced before the route path codec constructs a [[LiveLocation]].
    *
    * @param pathParams
    *   values to encode with the route's path codec
    * @param queryParams
    *   query parameters to attach; final URL rendering applies percent encoding
    */
  final case class Encoded[PathParams](
    pathParams: PathParams,
    queryParams: QueryParams)

  /** A routed parameter decoding failure.
    *
    * Initial failures prevent the routed LiveView from mounting. During connected parameter
    * changes, [[LiveView.Routed.handleParamsDecodeError]] may recover them.
    *
    * @param message
    *   a user-readable description of the invalid parameters
    * @param cause
    *   the underlying decoder failure, when one is available
    */
  final case class DecodeError(
    message: String,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  /** Uses the decoded path value itself as the routed parameters.
    *
    * Encoding preserves the path value and emits no query parameters.
    *
    * @return
    *   an identity codec for path values
    */
  def path[A]: LiveParamsCodec[A, A] =
    custom(
      decodeFn = (pathParams, _) => Right(pathParams),
      encodeFn = pathParams => Right(Encoded(pathParams, QueryParams.empty))
    )

  /** A codec for routes with neither path values nor routed parameters. */
  val none: LiveParamsCodec[Unit, Unit] = path[Unit]

  /** Derives query-only parameters from a ZIO Schema.
    *
    * @return
    *   a codec with no path values and schema-derived query parameters
    */
  def query[A](using Schema[A]): LiveParamsCodec[Unit, A] =
    fromZioHttp(HttpCodec.query[A])

  /** Adapts a ZIO HTTP query codec to a query-only Live parameter codec.
    *
    * @param codec
    *   the query codec used for request decoding and encoding
    * @return
    *   a Live parameter codec whose path value is `Unit`
    */
  def fromZioHttp[A](codec: QueryCodec[A]): LiveParamsCodec[Unit, A] =
    fromQuery(codec)

  /** Combines route path values with parameters handled by a ZIO HTTP query codec.
    *
    * The implicit [[zio.http.codec.Combiner]] determines the resulting tuple or simplified value
    * and separates it again during encoding. Query encoding exceptions are caught and returned as
    * [[LiveLocation.EncodeError.Query]].
    *
    * @param codec
    *   the query codec used for request decoding and encoding
    * @param combiner
    *   combines path and query values and separates them for encoding
    * @return
    *   a codec for the combiner's output type
    */
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

  /** Creates a bidirectional parameter codec from application functions.
    *
    * A string returned by `decodeFn` is normalized to `DecodeError(message)`. Both functions are
    * expected to be total: thrown exceptions are not converted into their declared error channels.
    * `encodeFn` should classify path and query failures with the corresponding
    * [[LiveLocation.EncodeError]].
    *
    * @param decodeFn
    *   decodes path values and the complete URL
    * @param encodeFn
    *   encodes typed parameters to route path/query values
    * @return
    *   a bidirectional custom codec
    */
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
