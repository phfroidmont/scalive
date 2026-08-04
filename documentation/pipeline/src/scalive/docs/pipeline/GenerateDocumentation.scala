package scalive.docs.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import zio.json.*

object GenerateDocumentation:
  def main(arguments: Array[String]): Unit =
    arguments.toList match
      case repository :: content :: output :: allowedRoots if allowedRoots.nonEmpty =>
        ContentPipeline.generate(
          repositoryRoot = Path.of(repository),
          contentRoot = Path.of(content),
          allowedSourceRoots = allowedRoots.map(Path.of(_))
        ) match
          case Left(error)   => throw new IllegalArgumentException(error.message)
          case Right(bundle) =>
            val outputPath = Path.of(output)
            val _          = Files.createDirectories(outputPath.getParent)
            val _          = Files.writeString(
              outputPath,
              bundle.toJson + "\n",
              StandardCharsets.UTF_8
            )
      case _ =>
        throw new IllegalArgumentException(
          "Expected repository root, content root, output path, and at least one allowed source root."
        )
