package scalive

import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

import zio.*

object StaticAssetHasher:
  private val cache = new ConcurrentHashMap[String, (String, Long, Long)]()

  private val defaultRoot: Path = Paths.get("public")

  private def key(root: Path, rel: String): String =
    s"${root.toAbsolutePath.normalize().toString}::${rel}"

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"$b%02x").mkString

  def hashedPath(rel: String, root: Path = defaultRoot): Task[String] =
    ZIO.attempt {
      val leadingSlash = rel.startsWith("/")
      val relClean     = if leadingSlash then rel.drop(1) else rel
      val keyStr       = key(root, relClean)

      val path = root.resolve(relClean).normalize()
      if !Files.exists(path) || !Files.isRegularFile(path) then
        throw new IllegalArgumentException(s"Static asset not found: $path")

      val attrs      = Files.readAttributes(path, classOf[java.nio.file.attribute.BasicFileAttributes])
      val lastMod    = attrs.lastModifiedTime().toMillis
      val size       = attrs.size()
      val cached     = Option(cache.get(keyStr))
      val cachedHash = cached.collect { case (h, lm, sz) if lm == lastMod && sz == size => h }

      val hashedName = cachedHash.getOrElse {
        val bytes = Files.readAllBytes(path)
        val md5   = MessageDigest.getInstance("MD5")
        val hash  = hex(md5.digest(bytes)) // full 32 hex chars

        val p           = Paths.get(relClean)
        val fileName    = p.getFileName.toString
        val dotIdx      = fileName.lastIndexOf('.')
        val (stem, ext) = if dotIdx >= 0 then fileName.splitAt(dotIdx) else (fileName, "")
        val extOut      = if ext.isEmpty then "" else ext
        val parentDir   = Option(p.getParent).map(_.toString.replace('\\', '/'))
        val name        = s"$stem-$hash$extOut"
        val fullName    = parentDir.map(d => s"$d/$name").getOrElse(name)

        cache.put(keyStr, (fullName, lastMod, size))
        fullName
      }

      if leadingSlash then s"/$hashedName" else hashedName
    }
end StaticAssetHasher
