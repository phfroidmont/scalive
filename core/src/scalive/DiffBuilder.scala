package scalive

import zio.Chunk
import zio.json.ast.Json

object DiffBuilder:
  def build(rendered: Rendered, fingerprint: Fingerprint): Json =
    val nestedFingerprintIter = fingerprint.nested.iterator
    Json.Obj(
      Option
        .when(rendered.static.nonEmpty && fingerprint.value != rendered.fingerprint)(
          "s" -> Json.Arr(rendered.static.map(Json.Str(_))*)
        )
        .to(Chunk)
        .appendedAll(
          rendered.dynamic.zipWithIndex
            .map((render, index) => index.toString -> build(render(true), nestedFingerprintIter))
        ).filterNot(_._2 == Json.Obj.empty)
    )

  private def build(comp: Comprehension, fingerprint: Fingerprint): Json =
    val nestedFingerprintIter = fingerprint.nested.iterator
    Json.Obj(
      Option
        .when(comp.static.nonEmpty && fingerprint.value != comp.fingerprint)(
          "s" -> Json.Arr(comp.static.map(Json.Str(_))*)
        )
        .to(Chunk)
        .appendedAll(
          Option.when(comp.entries.nonEmpty)(
            "d" ->
              Json.Arr(
                comp.entries.map(render =>
                  Json.Obj(
                    render(true).zipWithIndex
                      .map((dyn, index) =>
                        index.toString -> build(dyn, nestedFingerprintIter)
                      ).filterNot(_._2 == Json.Obj.empty)*
                  )
                )*
              )
          )
        )
    )

  private def build(dyn: RenderedDyn, fingerprintIter: Iterator[Fingerprint]): Json =
    dyn match
      case Some(s: String)   => Json.Str(s)
      case Some(r: Rendered) => build(r, fingerprintIter.nextOption.getOrElse(Fingerprint.empty))
      case Some(c: Comprehension) =>
        build(c, fingerprintIter.nextOption.getOrElse(Fingerprint.empty))
      case None => Json.Obj.empty
end DiffBuilder
