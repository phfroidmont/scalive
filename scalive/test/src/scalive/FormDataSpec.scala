package scalive

import zio.*
import zio.http.{Body, Form as HttpForm, FormField as HttpFormField, MediaType}
import zio.stream.ZStream
import zio.test.*

object FormDataSpec extends ZIOSpecDefault:
  private def decoded(value: String): FormData =
    FormData.fromUrlEncoded(value).fold(error => throw new AssertionError(error), identity)

  override def spec = suite("FormDataSpec")(
    test("preserves ordered repeated fields") {
      val data = decoded(
        "my_form%5Busers_sort%5D%5B%5D=0&my_form%5Busers_sort%5D%5B%5D=new&name=Alice+Smith"
      )

      assertTrue(
        data.raw == Vector(
          "my_form[users_sort][]" -> "0",
          "my_form[users_sort][]" -> "new",
          "name"                  -> "Alice Smith"
        ),
        data.values("my_form[users_sort][]") == Vector("0", "new"),
        data.string("name") == Some("Alice Smith")
      )
    },
    test("provides a lossy last-value map for existing APIs") {
      val data = decoded("field=old&field=new&empty=")

      assertTrue(
        data.asMap == Map("field" -> "new", "empty" -> ""),
        data.getOrElse("missing", "fallback") == "fallback"
      )
    },
    test("extracts direct nested fields") {
      val data = decoded(
        "my_form%5Bname%5D=Test&my_form%5Busers%5D%5B0%5D%5Bname%5D=User+A"
      )

      val nested = data.nested("my_form")

      assertTrue(
        nested.string("name") == Some("Test"),
        nested.string("users[0][name]") == Some("User A")
      )
    },
    test("reports malformed URL encoding") {
      assertTrue(
        FormData.fromUrlEncoded("name=%ZZ").left.exists {
          case FormData.RepresentationError.InvalidUrlEncoding(_) => true
          case _                                                     => false
        }
      )
    },
    test("decodes bounded URL-encoded bodies without losing repeated fields") {
      val body = Body
        .fromString("tag=first&tag=second&profile%5Bname%5D=Alice")
        .contentType(MediaType.application.`x-www-form-urlencoded`)

      for data <- FormData.fromUrlEncodedBody(body, maxBytes = 1024)
      yield assertTrue(
        data.values("tag") == Vector("first", "second"),
        data.string("profile[name]").contains("Alice")
      )
    },
    test("rejects an oversized streaming URL-encoded body") {
      val body = Body
        .fromStreamChunked(ZStream.fromIterable("name=Alice".getBytes.toIndexedSeq))
        .contentType(MediaType.application.`x-www-form-urlencoded`)

      for result <- FormData.fromUrlEncodedBody(body, maxBytes = 4).either
      yield assertTrue(
        result == Left(FormData.DecodeError.Body(FormData.BodyError.TooLarge(4)))
      )
    },
    test("rejects bodies with the wrong content type") {
      val body = Body.fromString("name=Alice").contentType(MediaType.application.json)

      for result <- FormData.fromUrlEncodedBody(body, maxBytes = 1024).either
      yield assertTrue(
        result.left.exists {
          case FormData.DecodeError.Representation(
                FormData.RepresentationError.InvalidContentType(Some(actual))
              ) =>
            actual.matches(MediaType.application.json)
          case _ => false
        }
      )
    },
    test("reports body stream failures separately from form decoding") {
      val failure = new IllegalStateException("stream failed")
      val body = Body
        .fromStreamChunked(ZStream.fail(failure))
        .contentType(MediaType.application.`x-www-form-urlencoded`)

      for result <- FormData.fromUrlEncodedBody(body, maxBytes = 1024).either
      yield assertTrue(
        result == Left(FormData.DecodeError.Body(FormData.BodyError.Read(failure)))
      )
    },
    test("adapts ordered textual ZIO HTTP form fields") {
      val form = HttpForm(
        HttpFormField.Simple("tag", "first"),
        HttpFormField.Text("profile[name]", "Alice", MediaType.text.plain),
        HttpFormField.Simple("tag", "second")
      )

      assertTrue(
        FormData.fromZioHttpForm(form).exists(
          _.raw == Vector(
            "tag"           -> "first",
            "profile[name]" -> "Alice",
            "tag"           -> "second"
          )
        )
      )
    },
    test("rejects binary ZIO HTTP fields without returning partial data") {
      val form = HttpForm(
        HttpFormField.Simple("name", "Alice"),
        HttpFormField.Binary(
          "avatar",
          Chunk(1.toByte),
          MediaType.application.`octet-stream`
        )
      )

      assertTrue(
        FormData.fromZioHttpForm(form) == Left(
          FormData.RepresentationError.UnsupportedField(
            "avatar",
            FormData.UnsupportedFieldKind.Binary
          )
        )
      )
    },
    test("rejects streaming ZIO HTTP fields without consuming them") {
      for
        consumed <- Ref.make(false)
        stream = ZStream.fromZIO(consumed.set(true)).drain ++ ZStream.succeed(1.toByte)
        form = HttpForm(
                 HttpFormField.StreamingBinary(
                   "avatar",
                   MediaType.application.`octet-stream`,
                   data = stream
                 )
               )
        result      = FormData.fromZioHttpForm(form)
        wasConsumed <- consumed.get
      yield assertTrue(
        result == Left(
          FormData.RepresentationError.UnsupportedField(
            "avatar",
            FormData.UnsupportedFieldKind.StreamingBinary
          )
        ),
        !wasConsumed
      )
    }
  )
end FormDataSpec
