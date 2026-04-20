import zio.json.ast.Json

import scalive.*

object E2ESandboxEval:
  def handle[Model](model: Model, event: String, value: Json): InterceptResult[Model] =
    if event != "sandbox:eval" then InterceptResult.cont(model)
    else
      val code =
        value match
          case Json.Obj(fields) =>
            fields.collectFirst { case ("value", Json.Str(v)) => v }.getOrElse("")
          case _ => ""

      val result =
        code match
          case "socket.assigns.items" =>
            extractProductField(model, "items").map(toJson).getOrElse(Json.Null)
          case "socket.assigns" => toJson(model)
          case _                => Json.Null

      InterceptResult.haltReply(model, Json.Obj("result" -> result))

  private def extractProductField(value: Any, name: String): Option[Any] =
    value match
      case product: Product =>
        product.productElementNames.zip(product.productIterator).collectFirst {
          case (fieldName, fieldValue) if fieldName == name => fieldValue
        }
      case _ => None

  private def toJson(value: Any): Json =
    value match
      case null                            => Json.Null
      case s: String                       => Json.Str(s)
      case b: Boolean                      => Json.Bool(b)
      case i: Int                          => Json.Num(i)
      case l: Long                         => Json.Num(l)
      case d: Double                       => Json.Num(d)
      case f: Float                        => Json.Num(f.toDouble)
      case bi: BigInt                      => Json.Num(bi)
      case bd: BigDecimal                  => Json.Num(bd)
      case map: scala.collection.Map[?, ?] =>
        Json.Obj(map.toSeq.collect { case (k: String, v) => k -> toJson(v) }*)
      case seq: Iterable[?]  => Json.Arr(seq.toSeq.map(toJson)*)
      case arr: Array[?]     => Json.Arr(arr.toSeq.map(toJson)*)
      case option: Option[?] => option.map(toJson).getOrElse(Json.Null)
      case product: Product  =>
        Json.Obj(
          product.productElementNames
            .zip(product.productIterator)
            .map { case (k, v) => k -> toJson(v) }
            .toSeq*
        )
      case other => Json.Str(other.toString)
end E2ESandboxEval
