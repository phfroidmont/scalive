package scalive.protocol.phoenix

import zio.Chunk
import zio.json.ast.Json
import zio.test.*

object PhoenixProtocolFuzzSpec extends ZIOSpecDefault:
  private val text = Gen.stringBounded(0, 16)(
    Gen.elements('a', 'Z', '0', ':', '-', '_', '/', '.', ' ', '\u0000', '\u20ac')
  )

  private val leaf: Gen[Any, Json] = Gen.oneOf(
    Gen.const(Json.Null),
    Gen.boolean.map(Json.Bool(_)),
    Gen.int(-1000, 1000).map(value => Json.Num(BigDecimal(value))),
    text.map(Json.Str(_))
  )

  private val shallow: Gen[Any, Json] = Gen.oneOf(
    leaf,
    Gen.listOfBounded(0, 4)(leaf).map(values => Json.Arr(values*)),
    Gen
      .listOfBounded(0, 4)(text.zip(leaf))
      .map(fields => Json.Obj(fields*))
  )

  private val jsonLike: Gen[Any, Json] = Gen.oneOf(
    shallow,
    Gen.listOfBounded(0, 7)(shallow).map(values => Json.Arr(values*)),
    Gen
      .listOfBounded(0, 4)(text.zip(shallow))
      .map(fields => Json.Obj(fields*))
  )

  private val envelopeShape: Gen[Any, Json] =
    Gen.listOfBounded(0, 7)(jsonLike).map(values => Json.Arr(values*))

  private val bytes: Gen[Any, Byte] = Gen.int(Byte.MinValue, Byte.MaxValue).map(_.toByte)
  private val shortBinary = Gen.listOfBounded(0, 4)(bytes).map(Chunk.fromIterable)
  private val arbitraryBinary = Gen.listOfBounded(0, 32)(bytes).map(Chunk.fromIterable)

  private def returned[A, B](value: Either[A, B]): Boolean = value.fold(_ => true, _ => true)

  override def spec = suite("PhoenixProtocolFuzzSpec")(
    test("bounded JSON-like envelopes and upload payloads always return Either") {
      check(envelopeShape) { json =>
        val envelope = PhoenixEnvelope.fromJson(json)
        val inbound  = PhoenixProtocol.decode(json)
        val uploadResults = List(
          PhoenixUploadProtocol.decodeJoin(json),
          PhoenixUploadProtocol.decodePreflight(json),
          PhoenixUploadProtocol.decodeProgress(json)
        )
        val eventUploads = json match
          case obj: Json.Obj => PhoenixUploadProtocol.decodeEventUploads(obj)
          case _             => Right(Vector.empty)

        assertTrue(
          returned(envelope),
          returned(inbound),
          uploadResults.forall(returned),
          returned(eventUploads)
        )
      }
    },
    test("arbitrary bounded strings always return from both JSON decoders") {
      check(text) { value =>
        assertTrue(
          returned(PhoenixEnvelope.decode(value)),
          returned(PhoenixProtocol.decode(value))
        )
      }
    },
    test("accepted join credentials remain opaque parser data") {
      check(text, text) { (session, token) =>
        val rootJoin = Json.Arr(
          Json.Null,
          Json.Str("1"),
          Json.Str("lv:root"),
          Json.Str("phx_join"),
          Json.Obj(
            "session" -> Json.Str(session),
            "params"  -> Json.Obj.empty
          )
        )
        val uploadJoin = Json.Arr(
          Json.Null,
          Json.Str("2"),
          Json.Str("lvu:entry"),
          Json.Str("phx_join"),
          Json.Obj("token" -> Json.Str(token))
        )

        assertTrue(
          PhoenixProtocol.decode(rootJoin).exists {
            case PhoenixInbound.Join(_, _, _, payload) => payload.session == session
            case _                                     => false
          },
          PhoenixProtocol.decode(uploadJoin).exists {
            case PhoenixInbound.UploadJoin(_, _, _, "entry", parsedToken) => parsedToken == token
            case _                                                         => false
          }
        )
      }
    },
    test("arbitrary short upload binary frames never defect") {
      check(shortBinary)(frame => assertTrue(returned(PhoenixUploadProtocol.decodeBinary(frame))))
    },
    test("arbitrary bounded upload binary shapes never defect") {
      check(arbitraryBinary)(frame => assertTrue(returned(PhoenixUploadProtocol.decodeBinary(frame))))
    }
  ) @@ TestAspect.samples(64)
end PhoenixProtocolFuzzSpec
