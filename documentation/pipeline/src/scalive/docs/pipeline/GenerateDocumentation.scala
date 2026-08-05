package scalive.docs.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import zio.json.*

object GenerateDocumentation:
  def main(arguments: Array[String]): Unit =
    arguments.toList match
      case repository :: content :: output :: revision :: settingsPath :: snapshotPath :: targetRoots :: dependencyClasspath :: allowedRoots
          if allowedRoots.nonEmpty =>
        val generated = for
          api <- ApiReferenceFiles.generateReference(
                   Path.of(repository),
                   revision,
                   Path.of(settingsPath),
                   paths(targetRoots),
                   paths(dependencyClasspath)
                 )
          (settings, reference) = api
          _        <- ApiSnapshot.validateSummaries(reference, settings.summaries)
          snapshot <- ApiReferenceFiles.loadSnapshot(Path.of(snapshotPath))
          _        <- ApiSnapshot.validate(reference, snapshot)
          bundle   <- ContentPipeline
                      .generate(
                        repositoryRoot = Path.of(repository),
                        contentRoot = Path.of(content),
                        allowedSourceRoots = allowedRoots.map(Path.of(_)),
                        apiReference = reference
                      ).left.map(error => ApiReferenceError(error.messages))
        yield bundle

        generated match
          case Left(error)   => throw new IllegalArgumentException(error.message)
          case Right(bundle) =>
            val outputRoot = Path.of(output)
            write(
              outputRoot.resolve("scalive/docs/generated/content.json"),
              bundle.toJson + "\n"
            )
            write(
              outputRoot.resolve("public/search-index.json"),
              bundle.searchEntries.toJson + "\n"
            )
      case _ =>
        throw new IllegalArgumentException(
          "Expected repository root, content root, output root, revision, API settings, API snapshot, target roots, dependency classpath, and at least one allowed source root."
        )

  private def write(path: Path, content: String): Unit =
    val _ = Files.createDirectories(path.getParent)
    val _ = Files.writeString(path, content, StandardCharsets.UTF_8)

  private def paths(value: String): Vector[Path] =
    value.split(java.io.File.pathSeparator).toVector.filter(_.nonEmpty).map(Path.of(_))
end GenerateDocumentation
