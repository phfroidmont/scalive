package scalive.docs.pipeline

import java.nio.file.Path

object ApiReferenceTool:
  def main(arguments: Array[String]): Unit =
    arguments.toList match
      case mode :: repository :: revision :: settingsPath :: snapshotPath :: targetRoots :: dependencyClasspath :: output =>
        val result = for
          generated <- ApiReferenceFiles.generateReference(
                         Path.of(repository),
                         revision,
                         Path.of(settingsPath),
                         paths(targetRoots),
                         paths(dependencyClasspath)
                       )
          (settings, reference) = generated
          _ <- mode match
                 case "check" =>
                   for
                     _        <- ApiSnapshot.validateSummaries(reference, settings.summaries)
                     snapshot <- ApiReferenceFiles.loadSnapshot(Path.of(snapshotPath))
                     _        <- ApiSnapshot.validate(reference, snapshot)
                   yield ()
                 case "update" =>
                   ApiSnapshot
                     .validateSummaries(reference, settings.summaries)
                     .flatMap(_ =>
                       ApiReferenceFiles.writeSnapshot(Path.of(snapshotPath), reference)
                     )
                 case "report" =>
                   output match
                     case path :: Nil => ApiReferenceFiles.writeReference(Path.of(path), reference)
                     case _           =>
                       Left(ApiReferenceError(Vector("Report mode requires an output path.")))
                 case other =>
                   Left(ApiReferenceError(Vector(s"Unknown API reference tool mode: $other")))
        yield ()

        result.left.foreach(error => throw IllegalArgumentException(error.message))
      case _ =>
        throw IllegalArgumentException(
          "Expected mode, repository, revision, settings, snapshot, target roots, dependency classpath, and optional output."
        )

  private def paths(value: String): Vector[Path] =
    value.split(java.io.File.pathSeparator).toVector.filter(_.nonEmpty).map(Path.of(_))
end ApiReferenceTool
