package scalive

import zio.*
import zio.http.*
import zio.http.codec.{HttpCodec, QueryCodec}
import zio.schema.Schema

trait LiveQueryCodec[A]:
  type Out = A

  def decode(url: URL): IO[LiveQueryCodec.DecodeError, A]
  def href(value: A): Either[LiveQueryCodec.EncodeError, String]

object LiveQueryCodec:
  final case class DecodeError(
    message: String,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  final case class EncodeError(
    message: String,
    cause: Option[Throwable] = None)
      extends RuntimeException(message, cause.orNull)

  val none: LiveQueryCodec[Unit] =
    custom(_ => Right(()), _ => Right("?"))

  def apply[A](using Schema[A]): LiveQueryCodec[A] =
    fromZioHttp(HttpCodec.query[A])

  def fromZioHttp[A](codec: QueryCodec[A]): LiveQueryCodec[A] =
    new LiveQueryCodec[A]:
      def decode(url: URL): IO[DecodeError, A] =
        codec
          .decodeRequest(Request.get(url))
          .mapError(toDecodeError)

      def href(value: A): Either[EncodeError, String] =
        try
          val encoded = codec.encodeRequest(value).url.encode
          Right(toQueryHref(encoded))
        catch case error: Throwable => Left(toEncodeError(error))

  def custom[A](
    decodeFn: URL => Either[String, A],
    encodeFn: A => Either[String, String]
  ): LiveQueryCodec[A] =
    new LiveQueryCodec[A]:
      def decode(url: URL): IO[DecodeError, A] =
        ZIO.fromEither(decodeFn(url).left.map(DecodeError(_)))

      def href(value: A): Either[EncodeError, String] =
        encodeFn(value).left
          .map(EncodeError(_))
          .flatMap(normalizeQueryHref)

  private def toQueryHref(encoded: String): String =
    if encoded == "/" then "?"
    else if encoded.startsWith("/?") then encoded.drop(1)
    else if encoded.startsWith("?") then encoded
    else
      val queryStart = encoded.indexOf('?')
      if queryStart >= 0 then encoded.substring(queryStart)
      else "?"

  private def normalizeQueryHref(href: String): Either[EncodeError, String] =
    if href.isEmpty then Right("?")
    else if href == "?" || href.startsWith("?") then Right(href)
    else
      Left(
        EncodeError(
          s"Expected query-only href starting with '?', got '$href'"
        )
      )

  private def toDecodeError(error: Throwable): DecodeError =
    DecodeError(Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString), Some(error))

  private def toEncodeError(error: Throwable): EncodeError =
    EncodeError(Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.toString), Some(error))
end LiveQueryCodec
