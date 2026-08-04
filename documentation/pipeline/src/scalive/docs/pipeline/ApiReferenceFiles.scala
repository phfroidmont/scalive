package scalive.docs.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import zio.json.*

import scalive.docs.model.*

final case class ApiReferenceSettings(
  repositoryUrl: String,
  domTypesVersion: String,
  domGeneratorPath: String,
  summaries: Map[String, String])
    derives JsonCodec:
  def metadata(revision: String): ApiReferenceMetadata =
    ApiReferenceMetadata(repositoryUrl, revision, domTypesVersion, domGeneratorPath)

object ApiReferenceFiles:
  def generateReference(
    repositoryRoot: Path,
    revision: String,
    settingsPath: Path,
    targetRoots: Seq[Path],
    dependencyClasspath: Seq[Path]
  ): Either[ApiReferenceError, (ApiReferenceSettings, ApiReference)] =
    for
      settings  <- loadSettings(settingsPath)
      _         <- validateSettings(repositoryRoot, revision, settings)
      reference <- ApiReferencePipeline.generate(
                     ApiReferenceConfig(
                       repositoryRoot,
                       targetRoots,
                       dependencyClasspath,
                       settings.metadata(revision),
                       settings.summaries
                     )
                   )
    yield settings -> reference

  def loadSettings(path: Path): Either[ApiReferenceError, ApiReferenceSettings] =
    read(path, "API reference settings").flatMap(
      _.fromJson[ApiReferenceSettings].left.map(message =>
        ApiReferenceError(Vector(s"Invalid API reference settings: $message"))
      )
    )

  def loadSnapshot(path: Path): Either[ApiReferenceError, ApiSnapshot] =
    read(path, "public API snapshot").flatMap(
      _.fromJson[ApiSnapshot].left.map(message =>
        ApiReferenceError(Vector(s"Invalid public API snapshot: $message"))
      )
    )

  def writeSnapshot(path: Path, reference: ApiReference): Either[ApiReferenceError, Unit] =
    write(path, ApiSnapshot.from(reference).toJsonPretty + "\n", "public API snapshot")

  def writeReference(path: Path, reference: ApiReference): Either[ApiReferenceError, Unit] =
    write(path, reference.toJsonPretty + "\n", "API reference report")

  def validateSettings(
    repositoryRoot: Path,
    revision: String,
    settings: ApiReferenceSettings
  ): Either[ApiReferenceError, Unit] =
    val errors = Vector.newBuilder[String]
    if !revision.matches("[0-9a-f]{40}") then
      errors += "Source revision must be a full lowercase 40-character Git SHA."
    if !settings.repositoryUrl.startsWith("https://") then
      errors += "API repository URL must use HTTPS."
    if settings.domGeneratorPath != "DomDefsGenerator.mill" then
      errors += "DOM generator path must be 'DomDefsGenerator.mill'."

    val buildFile = repositoryRoot.resolve("build.mill")
    try
      val build = Files.readString(buildFile, StandardCharsets.UTF_8)
      if !build.contains(s"com.raquo::domtypes:${settings.domTypesVersion}") then
        errors += s"DOM Types ${settings.domTypesVersion} does not match build.mill."
    catch case _: Exception => errors += "Unable to validate the DOM Types version in build.mill."

    val result = errors.result()
    Either.cond(result.isEmpty, (), ApiReferenceError(result))

  private def read(path: Path, description: String): Either[ApiReferenceError, String] =
    try Right(Files.readString(path, StandardCharsets.UTF_8))
    catch
      case _: Exception => Left(ApiReferenceError(Vector(s"Unable to read $description: $path")))

  private def write(
    path: Path,
    content: String,
    description: String
  ): Either[ApiReferenceError, Unit] =
    try
      Option(path.getParent).foreach(Files.createDirectories(_))
      val _ = Files.writeString(path, content, StandardCharsets.UTF_8)
      Right(())
    catch
      case _: Exception => Left(ApiReferenceError(Vector(s"Unable to write $description: $path")))
end ApiReferenceFiles
