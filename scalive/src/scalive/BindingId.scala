package scalive

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

private[scalive] object BindingId:
  type Path = Vector[String]

  private val UnresolvedValue = "__scalive_pending_binding_id__"
  private val HashSizeBytes   = 10
  private val HexDigits       = "0123456789abcdef"

  def unresolved(): String = UnresolvedValue

  def rootPath(tagName: String): Path =
    Vector(s"root:$tagName")

  def childTagPath(parent: Path, index: Int, tagName: String): Path =
    parent :+ s"tag:$index:$tagName"

  def childComponentPath(parent: Path, index: Int, cid: Int): Path =
    parent :+ s"component:$index:$cid"

  def childKeyedPath(parent: Path, index: Int): Path =
    parent :+ s"keyed:$index"

  def keyedEntryPath(parent: Path, key: Any): Path =
    parent :+ s"entry:${stableToken(stableKeySeed(key), 6)}"

  def attrBindingId(path: Path, attrIndex: Int): String =
    idFromSeed(s"${pathSeed(path)}|attr:$attrIndex")

  def jsBindingScope(path: Path, attrIndex: Int): String =
    s"${pathSeed(path)}|js:$attrIndex"

  def jsPushBindingId(scope: String, pushIndex: Int): String =
    idFromSeed(s"$scope|push:$pushIndex")

  private def idFromSeed(seed: String): String =
    s"b${stableToken(seed, HashSizeBytes)}"

  private def pathSeed(path: Path): String =
    path.mkString("/")

  private def stableKeySeed(key: Any): String =
    val className = Option(key).map(_.getClass.getName).getOrElse("null")
    s"$className:${String.valueOf(key)}"

  private def stableToken(seed: String, bytes: Int): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8))
    val sb     = new StringBuilder(bytes * 2)
    var i      = 0
    while i < bytes do
      val b = digest(i) & 0xff
      sb.append(HexDigits.charAt((b >>> 4) & 0x0f))
      sb.append(HexDigits.charAt(b & 0x0f))
      i = i + 1
    sb.toString
end BindingId
