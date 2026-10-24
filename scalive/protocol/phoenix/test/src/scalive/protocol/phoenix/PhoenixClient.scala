package scalive.protocol.phoenix

import zio.json.ast.Json

/** How the bundled Phoenix LiveView client (`rendered.js`) merges rendered diffs and renders them.
  *
  * It follows `mergeDiff`, `mutableMerge`, `mergeKeyed`, and `toOutputBuffer`, including how
  * rendering resolves shared `p` templates in place and drops them. A dynamic the client would
  * have to render as `undefined` fails instead. Streams are out of scope: the client hands their
  * rows to DOM patching rather than keeping them in the rendered tree.
  */
final class PhoenixClient private (
  private var root: Json.Obj,
  private var components: Map[String, Json.Obj]):

  def merge(diff: Json.Obj): Unit =
    val fields = diff.fields.toMap
    val rest   = Json.Obj(diff.fields.filterNot(_._1 == "c")*)
    // A diff with root statics replaces the tree, components included.
    if fields.contains("s") then components = Map.empty
    root = mutableMerge(root, rest)
    fields.get("c").foreach { changed =>
      obj(changed).fields.foreach { case (cid, value) =>
        val diff = obj(value)
        components = components.updated(
          cid,
          components.get(cid) match
            case Some(previous) if !has(diff, "s") => doMutableMerge(previous, diff)
            case _                                 => diff
        )
      }
    }

  /** Renders the tree as the client does, keeping the statics it resolves along the way. */
  def html: String =
    val (output, resolved) = render(root, None)
    root = resolved
    output

  private def renderComponent(cid: String): String =
    val component = components.getOrElse(cid, fail(s"no component for CID $cid"))
    val (output, resolved) = render(component, None)
    components = components.updated(cid, resolved)
    output

  private def render(node: Json.Obj, inherited: Option[Json.Obj]): (String, Json.Obj) =
    if has(node, "k") then renderComprehension(node, inherited)
    else
      // A nested `p` replaces the inherited templates.
      val templates = field(node, "p").map(obj).orElse(inherited)
      val statics   = resolveStatics(node, templates)
      val (output, dynamics) = renderDynamics(node, statics, templates)
      output -> without(dynamics, "p").put("s", Json.Arr(statics.map(Json.Str(_))*))

  private def renderComprehension(node: Json.Obj, inherited: Option[Json.Obj])
    : (String, Json.Obj) =
    if has(node, "stream") then fail("streams are not modeled")
    // Unlike a nested `p`, a comprehension's own `p` only applies when none is inherited.
    val templates = inherited.orElse(field(node, "p").map(obj))
    val statics   = resolveStatics(node, inherited)
    val keyed     = obj(node.get("k"))
    val count     = int(keyed.get("kc"))
    val rendered  = (0 until count).map { index =>
      val entry = obj(keyed.get(index.toString))
      renderDynamics(entry, statics, templates)
    }
    val entries = rendered.zipWithIndex.map { case ((_, entry), index) => index.toString -> entry }
    val output  = rendered.map(_._1).mkString
    output -> without(node, "p")
      .put("s", Json.Arr(statics.map(Json.Str(_))*))
      .put("k", Json.Obj((entries :+ ("kc" -> Json.Num(count)))*))

  private def renderDynamics(
    node: Json.Obj,
    statics: Vector[String],
    templates: Option[Json.Obj]
  ): (String, Json.Obj) =
    val output   = StringBuilder()
    var resolved = node
    statics.indices.foreach { index =>
      output.append(statics(index))
      if index < statics.length - 1 then
        val key = index.toString
        node.get(key) match
          case Some(Json.Str(value)) => output.append(value)
          case Some(Json.Num(cid))   => output.append(renderComponent(cid.toString))
          case Some(child: Json.Obj) =>
            val (html, child2) = render(child, templates)
            output.append(html)
            resolved = resolved.put(key, child2)
          case other => fail(s"dynamic $key renders as undefined: $other")
    }
    output.result() -> resolved

  private def resolveStatics(node: Json.Obj, templates: Option[Json.Obj]): Vector[String] =
    node.get("s") match
      case Some(Json.Arr(values)) => values.map(string).toVector
      case Some(Json.Num(index))  =>
        val table = templates.getOrElse(fail("a template reference has no templates"))
        table.get(index.toString) match
          case Some(Json.Arr(values)) => values.map(string).toVector
          case _                      => fail(s"unknown template $index")
      case other => fail(s"rendered object has no statics: $other")

  private def mutableMerge(target: Json.Obj, source: Json.Obj): Json.Obj =
    if has(source, "s") then source else doMutableMerge(target, source)

  private def doMutableMerge(target: Json.Obj, source: Json.Obj): Json.Obj =
    if has(source, "k") then mergeKeyed(target, source)
    else
      source.fields.foldLeft(target) { case (merged, (key, value)) =>
        (value, merged.get(key)) match
          case (child: Json.Obj, Some(previous: Json.Obj)) if !has(child, "s") =>
            merged.put(key, doMutableMerge(previous, child))
          case _ => merged.put(key, value)
      }

  private def mergeKeyed(target: Json.Obj, source: Json.Obj): Json.Obj =
    val previous = obj(target.get("k"))
    val changes  = obj(source.get("k"))
    val count    = int(changes.get("kc"))
    val merged   = changes.fields.foldLeft(previous) {
      case (entries, ("kc" | "km", _))                    => entries
      case (entries, (index, Json.Arr(Seq(Json.Num(from), diff: Json.Obj)))) =>
        entries.put(index, doMutableMerge(obj(previous.get(from.toString)), diff))
      case (entries, (index, Json.Num(from))) =>
        entries.put(index, obj(previous.get(from.toString)))
      case (entries, (index, diff: Json.Obj)) =>
        val current = entries.get(index).map(obj).getOrElse(Json.Obj())
        entries.put(index, doMutableMerge(current, diff))
      case (_, (index, value)) => fail(s"unexpected keyed entry $index: $value")
    }
    val kept = merged.fields.filter { case (key, _) =>
      key == "kc" || key.toIntOption.exists(_ < count)
    }
    val result = target.put("k", Json.Obj(kept*).put("kc", Json.Num(count)))
    field(source, "p").fold(result)(templates => result.put("p", templates))

  // Json.Obj#add deep-merges objects; the client assigns them.
  extension (node: Json.Obj)
    private def put(key: String, value: Json): Json.Obj =
      Json.Obj((node.fields.filterNot(_._1 == key) :+ (key -> value))*)

  private def has(node: Json.Obj, key: String): Boolean = node.get(key).nonEmpty

  private def field(node: Json.Obj, key: String): Option[Json] = node.get(key)

  private def without(node: Json.Obj, key: String): Json.Obj =
    Json.Obj(node.fields.filterNot(_._1 == key)*)

  private def obj(value: Json): Json.Obj = value match
    case value: Json.Obj => value
    case other           => fail(s"expected an object: $other")

  private def obj(value: Option[Json]): Json.Obj = obj(value.getOrElse(fail("missing object")))

  private def int(value: Option[Json]): Int = value match
    case Some(Json.Num(number)) => number.intValue
    case other                  => fail(s"expected a number: $other")

  private def string(value: Json): String = value match
    case Json.Str(text) => text
    case other          => fail(s"expected a string: $other")

  private def fail(message: String): Nothing = throw AssertionError(message)
end PhoenixClient

object PhoenixClient:
  /** Joins with an initial rendered payload, as the client does on mount. */
  def join(initial: Json.Obj): PhoenixClient =
    val client = PhoenixClient(Json.Obj(), Map.empty)
    client.merge(initial)
    client
