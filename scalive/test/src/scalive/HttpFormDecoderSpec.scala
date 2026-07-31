package scalive

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

import zio.*
import zio.http.*
import zio.test.*

object HttpFormDecoderSpec extends ZIOSpecDefault:
  private val security = LiveSecurity(TokenConfig("http-form-decoder-secret", 1.hour))
  private val Name     = _root_.scalive.FormField.requiredString(FormPath("profile", "name"))

  private def request(body: String, includeCsrf: Boolean = true): Request =
    val prepared = security.csrf.prepare(Request.get(URL.root))
    val fields =
      if includeCsrf then s"${CsrfProtection.ParamName}=${prepared.value}&$body"
      else body
    Request
      .post(
        URL.root,
        Body.fromString(fields).contentType(MediaType.application.`x-www-form-urlencoded`)
      ).addCookie(Cookie.Request(prepared.cookie.get.name, prepared.cookie.get.content))

  def spec = suite("HttpFormDecoderSpec")(
    test("decodes bounded csrf-protected forms") {
      val decoder = HttpFormDecoder.urlEncoded(Name.codec, 1024, security.csrf)

      for result <- decoder.decode(request("profile%5Bname%5D=Alice"))
      yield assertTrue(result == "Alice")
    },
    test("keeps body representation csrf and validation failures distinct") {
      val decoder      = HttpFormDecoder.urlEncoded(Name.codec, 4096, security.csrf)
      val smallDecoder = HttpFormDecoder.urlEncoded(Name.codec, 32, security.csrf)
      val wrongType = Request.post(
        URL.root,
        Body.fromString("{}").contentType(MediaType.application.json)
      )

      for
        tooLarge <- smallDecoder.decode(request("x=" + ("a" * 100))).either
        malformed <- decoder.decode(request("profile%5Bname%5D=%ZZ")).either
        representation <- decoder.decode(wrongType).either
        csrf           <- decoder.decode(request("profile%5Bname%5D=Alice", includeCsrf = false)).either
        validation     <- decoder.decode(request("profile%5Bname%5D=")).either
      yield assertTrue(
        tooLarge.left.exists {
          case HttpFormDecoder.Error.Body(FormData.BodyError.TooLarge(32)) => true
          case _                                                           => false
        },
        malformed.left.exists {
          case HttpFormDecoder.Error.Representation(
                FormData.RepresentationError.InvalidUrlEncoding(_)
              ) => true
          case _ => false
        },
        representation.left.exists {
          case HttpFormDecoder.Error.Representation(
                FormData.RepresentationError.InvalidContentType(_)
              ) => true
          case _ => false
        },
        csrf.left.exists {
          case HttpFormDecoder.Error.Csrf(CsrfProtection.ValidationError.MissingToken) => true
          case _                                                                        => false
        },
        validation.left.exists {
          case HttpFormDecoder.Error.Validation(errors) => errors.forPath(Name.path).nonEmpty
          case _                                         => false
        }
      )
    },
    test("does not run application validation after earlier failures") {
      val calls = AtomicInteger()
      val codec = FormCodec[String] { _ =>
        calls.incrementAndGet()
        Right("decoded")
      }
      val decoder = HttpFormDecoder.urlEncoded(codec, 1024, security.csrf)

      for
        _ <- decoder.decode(request("value=%ZZ")).either
        _ <- decoder.decode(request("value=ok", includeCsrf = false)).either
      yield assertTrue(calls.get() == 0)
    },
    test("maps transport errors while delegating application validation") {
      val validationErrors = FormErrors.one(Name.path, "invalid")
      val validationCalls  = AtomicInteger()
      val errors = Vector[HttpFormDecoder.Error](
        HttpFormDecoder.Error.Body(FormData.BodyError.TooLarge(10)),
        HttpFormDecoder.Error.Body(FormData.BodyError.Read(new RuntimeException("read"))),
        HttpFormDecoder.Error.Representation(
          FormData.RepresentationError.InvalidContentType(Some(MediaType.application.json))
        ),
        HttpFormDecoder.Error.Representation(
          FormData.RepresentationError.InvalidUrlEncoding("bad encoding")
        ),
        HttpFormDecoder.Error.Representation(
          FormData.RepresentationError.UnsupportedField(
            "upload",
            FormData.UnsupportedFieldKind.Binary
          )
        ),
        HttpFormDecoder.Error.Csrf(CsrfProtection.ValidationError.MissingToken),
        HttpFormDecoder.Error.Validation(validationErrors)
      )
      val responses = errors.map(
        _.toResponse { actual =>
          validationCalls.incrementAndGet()
          Predef.assert(actual == validationErrors)
          Status.UnprocessableEntity.toResponse
        }
      )

      assertTrue(
        errors.map(_.code) == Vector(
          "body_too_large",
          "body_read",
          "invalid_content_type",
          "invalid_url_encoding",
          "unsupported_binary",
          "csrf",
          "validation"
        ),
        responses.map(_.status) == Vector(
          Status.RequestEntityTooLarge,
          Status.BadRequest,
          Status.UnsupportedMediaType,
          Status.BadRequest,
          Status.BadRequest,
          Status.Forbidden,
          Status.UnprocessableEntity
        ),
        validationCalls.get() == 1
      )
    }
  )
