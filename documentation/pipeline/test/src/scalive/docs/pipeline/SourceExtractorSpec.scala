package scalive.docs.pipeline

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

import zio.*
import zio.test.*

object SourceExtractorSpec extends ZIOSpecDefault:
  private val fixtureRepository =
    val resource = Option(
      getClass.getClassLoader.getResource("source-extractor/repository")
    ).getOrElse(throw new IllegalStateException("SourceExtractor fixtures are missing"))
    Path.of(resource.toURI)

  private val allowedRoots = Seq(Path.of("examples"))

  override def spec = suite("SourceExtractorSpec")(
    suite("valid regions")(
      test("extracts content with a normalized POSIX path and inclusive line range") {
        val result = SourceExtractor.extract(
          repositoryRoot = fixtureRepository,
          allowedRoots = allowedRoots,
          sourcePath = Path.of("examples/nested/../Valid.scala"),
          regionName = "greeting"
        )

        assertTrue(
          result == Right(
            ExtractedSource(
              path = "examples/Valid.scala",
              startLine = 4,
              endLine = 5,
              content = "val greeting = \"hello\"\nval audience = \"docs\""
            )
          )
        )
      },
      test("accepts trailing whitespace on marker lines") {
        withTempDirectory("source-extractor-markers-") { temporaryRoot =>
          ZIO.attemptBlocking {
            val repository = Files.createDirectory(temporaryRoot.resolve("repository"))
            val examples   = Files.createDirectory(repository.resolve("examples"))
            val source     = examples.resolve("Trailing.scala")
            val _ = Files.writeString(
              source,
              "// docs:start sample  \nval sample = true\n// docs:end sample  \n",
              StandardCharsets.UTF_8
            )
            val result = SourceExtractor.extract(
              repository,
              Seq(Path.of("examples")),
              Path.of("examples/Trailing.scala"),
              "sample"
            )

            assertTrue(
              result == Right(
                ExtractedSource(
                  path = "examples/Trailing.scala",
                  startLine = 2,
                  endLine = 2,
                  content = "val sample = true"
                )
              )
            )
          }
        }
      }
    ),
    suite("path validation")(
      test("rejects absolute source paths") {
        val absolutePath = fixtureRepository.resolve("examples/Valid.scala")
        assertError(
          SourceExtractor.extract(
            fixtureRepository,
            allowedRoots,
            absolutePath,
            "greeting"
          ),
          s"Source path must be repository-relative: '${posix(absolutePath)}'."
        )
      },
      test("rejects source paths that escape the repository") {
        assertError(
          SourceExtractor.extract(
            fixtureRepository,
            allowedRoots,
            Path.of("../Valid.scala"),
            "greeting"
          ),
          "Source path escapes the repository: '../Valid.scala'."
        )
      },
      test("rejects files outside the allowed roots") {
        assertError(
          SourceExtractor.extract(
            fixtureRepository,
            allowedRoots,
            Path.of("private/Secret.scala"),
            "secret"
          ),
          "Source file is outside the allowed roots: 'private/Secret.scala'."
        )
      },
      test("rejects absolute and escaped allowed roots") {
        val absoluteRoot = fixtureRepository.resolve("examples")
        val absoluteResult = SourceExtractor.extract(
          fixtureRepository,
          Seq(absoluteRoot),
          Path.of("examples/Valid.scala"),
          "greeting"
        )
        val escapedResult = SourceExtractor.extract(
          fixtureRepository,
          Seq(Path.of("../examples")),
          Path.of("examples/Valid.scala"),
          "greeting"
        )

        assertTrue(
          absoluteResult.left.map(_.message) == Left(
            s"Allowed root must be repository-relative: '${posix(absoluteRoot)}'."
          ),
          escapedResult.left.map(_.message) == Left(
            "Allowed root escapes the repository: '../examples'."
          )
        )
      },
      test("rejects missing source files") {
        assertError(
          SourceExtractor.extract(
            fixtureRepository,
            allowedRoots,
            Path.of("examples/Missing.scala"),
            "sample"
          ),
          "Source file does not exist: 'examples/Missing.scala'."
        )
      },
      test("rejects a symlink whose target is inside the repository but outside allowed roots") {
        withTempDirectory("source-extractor-inside-") { temporaryRoot =>
          ZIO.attemptBlocking {
            val repository = Files.createDirectory(temporaryRoot.resolve("repository"))
            val allowed    = Files.createDirectory(repository.resolve("allowed"))
            val privateDir = Files.createDirectory(repository.resolve("private"))
            val target     = privateDir.resolve("Secret.scala")
            val _ = Files.writeString(
              target,
              "// docs:start secret\nval secret = true\n// docs:end secret\n",
              StandardCharsets.UTF_8
            )
            val link = allowed.resolve("Secret.scala")
            val _    = Files.createSymbolicLink(link, target)

            assertError(
              SourceExtractor.extract(
                repository,
                Seq(Path.of("allowed")),
                Path.of("allowed/Secret.scala"),
                "secret"
              ),
              "Source file is outside the allowed roots: 'allowed/Secret.scala'."
            )
          }
        }
      },
      test("rejects a symlink whose target is outside the repository") {
        withTempDirectory("source-extractor-outside-") { temporaryRoot =>
          ZIO.attemptBlocking {
            val repository = Files.createDirectory(temporaryRoot.resolve("repository"))
            val allowed    = Files.createDirectory(repository.resolve("allowed"))
            val outside    = Files.createDirectory(temporaryRoot.resolve("outside"))
            val target     = outside.resolve("External.scala")
            val _ = Files.writeString(
              target,
              "// docs:start external\nval external = true\n// docs:end external\n",
              StandardCharsets.UTF_8
            )
            val link = allowed.resolve("External.scala")
            val _    = Files.createSymbolicLink(link, target)

            assertError(
              SourceExtractor.extract(
                repository,
                Seq(Path.of("allowed")),
                Path.of("allowed/External.scala"),
                "external"
              ),
              "Source file is outside the allowed roots: 'allowed/External.scala'."
            )
          }
        }
      }
    ),
    suite("marker validation")(
      test("rejects a missing start marker") {
        assertFixtureError(
          "MissingStart.scala",
          "sample",
          "Missing start marker for region 'sample' in 'examples/MissingStart.scala'."
        )
      },
      test("rejects a missing end marker") {
        assertFixtureError(
          "MissingEnd.scala",
          "sample",
          "Missing end marker for region 'sample' in 'examples/MissingEnd.scala'."
        )
      },
      test("rejects duplicate start markers") {
        assertFixtureError(
          "DuplicateStart.scala",
          "sample",
          "Duplicate start marker for region 'sample' at lines 1, 4 in 'examples/DuplicateStart.scala'."
        )
      },
      test("rejects duplicate end markers") {
        assertFixtureError(
          "DuplicateEnd.scala",
          "sample",
          "Duplicate end marker for region 'sample' at lines 3, 4 in 'examples/DuplicateEnd.scala'."
        )
      },
      test("rejects reversed marker pairs") {
        assertFixtureError(
          "Reversed.scala",
          "sample",
          "End marker precedes start marker for region 'sample' in 'examples/Reversed.scala'."
        )
      },
      test("rejects nested regions") {
        assertFixtureError(
          "Nested.scala",
          "outer",
          "Nested or overlapping regions 'outer' and 'inner' in 'examples/Nested.scala'."
        )
      },
      test("rejects overlapping regions") {
        assertFixtureError(
          "Overlapping.scala",
          "first",
          "Nested or overlapping regions 'first' and 'second' in 'examples/Overlapping.scala'."
        )
      },
      test("rejects malformed markers") {
        assertFixtureError(
          "Malformed.scala",
          "sample",
          "Malformed source marker at 'examples/Malformed.scala:1': '// docs:start sample extra'."
        )
      },
      test("rejects marker-like lines with malformed spacing") {
        assertFixtureError(
          "MalformedSpacing.scala",
          "sample",
          "Malformed source marker at 'examples/MalformedSpacing.scala:1': '//docs:start sample'."
        )
      },
      test("rejects empty and whitespace-only regions") {
        val empty = SourceExtractor.extract(
          fixtureRepository,
          allowedRoots,
          Path.of("examples/Empty.scala"),
          "sample"
        )
        val whitespace = SourceExtractor.extract(
          fixtureRepository,
          allowedRoots,
          Path.of("examples/Whitespace.scala"),
          "sample"
        )

        assertTrue(
          empty.left.map(_.message) == Left(
            "Region 'sample' is empty in 'examples/Empty.scala'."
          ),
          whitespace.left.map(_.message) == Left(
            "Region 'sample' is empty in 'examples/Whitespace.scala'."
          )
        )
      }
    )
  )

  private def assertFixtureError(file: String, region: String, message: String): TestResult =
    assertError(
      SourceExtractor.extract(
        fixtureRepository,
        allowedRoots,
        Path.of("examples").resolve(file),
        region
      ),
      message
    )

  private def assertError(
    result: Either[SourceExtractionError, ExtractedSource],
    message: String
  ): TestResult =
    assertTrue(result.left.map(_.message) == Left(message))

  private def posix(path: Path): String =
    path.iterator().asScala
      .map(_.toString).mkString(if path.isAbsolute then "/" else "", "/", "")

  private def withTempDirectory[A](prefix: String)(use: Path => Task[A]): Task[A] =
    ZIO.acquireReleaseWith(
      ZIO.attemptBlocking(Files.createTempDirectory(prefix))
    )(directory => deleteRecursively(directory).orDie)(use)

  private def deleteRecursively(directory: Path): Task[Unit] =
    ZIO.attemptBlocking {
      if Files.exists(directory) then
        val paths = Files.walk(directory)
        try
          paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
            val _ = Files.deleteIfExists(path)
          }
        finally paths.close()
    }
end SourceExtractorSpec
