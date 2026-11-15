{%
title = "Static assets"
description = "Choose between an asset tree and a deployment manifest, serve stylesheets and scripts from URLs browsers can cache, render tracked tags, and build a split bundle that writes its own manifest."
order = 3
section = guides
group = "Setup and foundations"
%}

## Before you start {#prerequisites}

You need a Scalive application such as the one in the
[quick start](../learn/quick-start.md), and files for browsers to load: a
stylesheet, a script, fonts, or images. Connecting the Phoenix LiveView client
is a separate subject, covered in [Client setup](client-setup.md); Scalive
serves its own browser files without any of the setup below.

Scalive serves application files in one of two ways. Choosing between them
takes one question: **does the build put content hashes in file names?**

## Choose an asset model {#choose-an-asset-model}

A browser may keep a file for a year only when its URL changes whenever its
bytes do, so something has to put a version in every URL. Either Scalive does
it, or the build already did:

| Does the build put content hashes in file names? | Model | Who versions | What a deployment changes |
| --- | --- | --- | --- |
| No: `app.css`, `app.js` | [Asset tree](#load-classpath-assets) | Scalive, with one version for the whole tree. No file is renamed, so relative references between files keep working. | Changing any file moves every URL. |
| Yes: `app-K3M7.js`, `chunks/editor-R4T8.js` | [Deployment manifest](#load-a-deployment-manifest) | The build, with a hash in each file name. A manifest it writes tells Scalive which files exist. | Only the files whose bytes changed move. |

An application with a hand-written stylesheet, or a bundler that writes a single
`app.js`, answers no and loads a tree with one line of Scala. A bundler that
splits code into chunks answers yes: it names each chunk after its content and
writes those names into the files that import them.

Both models serve every URL as immutable by default, and both load into the same
@:apiSymbol(class:scalive.StaticAssets)`StaticAssets`@:@ value, so
[routes, path lookup](#add-static-routes-and-resolve-paths) and
[tags](#render-tracked-tags) do not depend on the choice. Where the files are
stored is a separate argument, a
@:apiSymbol(trait:scalive.StaticAssetRoot)`StaticAssetRoot`@:@: a classpath
prefix or a directory, for either model.

Scalive's own browser files are a fixed tree below `/_scalive`, whichever model
the application uses.

## Load a classpath tree {#load-classpath-assets}

Load application assets from the classpath when the build packages them with
the application. The [layouts guide's](layouts.md#install-root-and-ordinary-layouts)
root layout loads a single stylesheet this way:

```scala fragment
assets <- StaticAssets.tree(StaticAssetRoot.classpath("public"))
```

@:apiSymbol(def:scalive.StaticAssets.tree)`StaticAssets.tree`@:@ serves every
regular file below the prefix, so a font or an image added to the tree needs no
Scala change, and relative imports and URLs between the files remain available.
The default mount path is `/static`, and original unversioned URLs are disabled
by default.

Everything below the prefix is published. The class loader looks for a
@:apiSymbol(def:scalive.StaticAssetRoot.classpath)`StaticAssetRoot.classpath`@:@
prefix in every directory and JAR it reads, and the tree is the union of what it
finds, so a dependency's files under the same prefix are served too. Choose a
prefix specific to the application, such as `my-app/public`, and keep nothing
below it that browsers must not read.

Discovery lists the prefix in directories and in JARs, so the build output
directory that development runs from and the JAR that production runs from serve
the same tree.

### Restrict a tree to listed files {#restrict-a-tree}

Pass `only` to serve exactly the files it lists. Nothing is discovered then, and
loading fails when a listed file is missing:

```scala fragment
assets <- StaticAssets.tree(
  StaticAssetRoot.classpath("public"),
  only = Some(Seq("app.css", "app.js"))
)
```

`only` is also the fix when discovery cannot list the prefix. Loading fails at
startup, with an error that says to pass `only`, when the class loader finds the
prefix somewhere that is neither a directory nor a JAR, or does not find it at
all. A JAR written without directory entries is the usual cause of the second:
its files are there, but the class loader has no location to give for the
prefix. The listed files are read by name, without listing anything.

Discovery cannot notice such a JAR when the class loader also finds the prefix
somewhere else, in a directory or in another JAR. Loading succeeds then, and the
tree is missing the files of the JAR without directory entries. Build such a JAR
with directory entries, or name its files in `only`.

A precompressed copy stored next to an asset, such as `app.js.br` or
`app.js.gz`, is served as an encoding of that asset to browsers that accept it.
It is not published as an asset of its own.

## Load a directory tree {#load-a-directory}

Use a @:apiSymbol(def:scalive.StaticAssetRoot.directory)`StaticAssetRoot.directory`@:@
root when assets are deployed outside the application classpath:

```scala fragment
import java.nio.file.Paths

assets <- StaticAssets.tree(
  StaticAssetRoot.directory(Paths.get("/srv/my-app/public"))
)
```

A directory tree also serves every regular file below its root, and `only`
limits it to the listed relative paths in the same way. Configured paths must be
normalized relative paths; empty segments, `.`, `..`, and backslashes are
rejected, and symlinks cannot escape the configured root. The mount path and
originals defaults match the classpath tree.

## Understand versioned paths {#serve-versioned-paths}

A tree serves every file below one version namespace, so render URLs from the
loaded `StaticAssets` value rather than hard-coding them.

Loading a tree computes one SHA-256 version for the complete asset set.
Filenames and relative directories remain unchanged beneath that namespace:
@:apiSymbol(def:scalive.StaticAssets.path)`StaticAssets.path("app.js")`@:@
returns a URL such as `/static/<asset-set-digest>/app.js`. A tree entry such as
`chunks/editor.js` is served at `/static/<asset-set-digest>/chunks/editor.js`.
Relative JavaScript imports, worker URLs, CSS URLs, fonts, and source maps
therefore resolve within the same versioned namespace without rewriting file
contents or inserting a digest into each filename. The same digest means that
changing any file moves every URL; see
[what a deployment invalidates](deployment.md#know-what-a-deployment-invalidates).

Versioned responses default to `public`, a one-year `max-age`, and `immutable`.
Original paths return `404` by default; set `serveOriginals = true` only when
unversioned access is required. Originals use `no-cache`. A request whose
`If-None-Match` names the response's `ETag` gets `304 Not Modified` without the
bytes.

Loading reads every asset once and keeps its bytes in memory, so the server
never reads an asset file again: a file changed in place is served as it was
loaded until `StaticAssets` is loaded again, usually by the next deployment.
Size the heap for the asset tree.

When a precompressed copy is stored next to an asset, such as `app.js.br` or
`app.js.gz`, it is loaded with it and sent, with `Content-Encoding`, to a
browser whose `Accept-Encoding` accepts it. Brotli is preferred to gzip. Each
copy has its own `ETag`, and responses for an asset with copies carry
`Vary: Accept-Encoding`. Scalive does not compress assets itself; produce the
copies in the asset build.

## Load a deployment manifest {#load-a-deployment-manifest}

When the build puts content hashes in file names, it also writes a deployment
manifest: a JSON file that names every file to serve. Load it with
@:apiSymbol(def:scalive.StaticAssets.manifest)`StaticAssets.manifest`@:@
from a classpath prefix or from a directory:

```scala fragment
import java.nio.file.Paths

assets <- StaticAssets.manifest(StaticAssetRoot.classpath("public"))

// Or for files outside the classpath:
assets <- StaticAssets.manifest(
  StaticAssetRoot.directory(Paths.get("/srv/my-app/public"))
)
```

The manifest name defaults to `assets-manifest.json` and the mount path to
`/static`; override the `name` or `mountPath` argument when needed. Version 1 of
the format is:

```json
{
  "version": 1,
  "assets": {
    "app.js": { "file": "app-K3M7.js", "cache": "immutable" },
    "app.css": { "file": "app-P9Q2.css", "cache": "immutable" },
    "chunks/editor-R4T8.js": { "file": "chunks/editor-R4T8.js", "cache": "immutable" },
    "robots.txt": { "file": "robots.txt", "cache": "revalidate" }
  }
}
```

Keys are logical aliases used with `path`, `script`, and `stylesheet`. Each
`file` is the exact relative path of the file below the root, and the path
Scalive serves it at below the mount path: `/static/app-K3M7.js` here, with no
version segment added. Give the top-level entries a stable alias, so that the
root layout does not mention a hashed name, and list every other deployable file
too. Chunks, fonts, maps, and workers can use their final path as their alias.
Nothing is discovered: a file the manifest does not list is not served, and the
manifest itself is not served unless it lists itself.

Scalive validates and hashes every declared file at startup. It rejects missing
files, path traversal or other non-normalized paths, unsupported cache values,
and conflicting cache policies for one final file, so a build that lists a file
it did not write never reaches users. Declare `immutable` only for a
content-hashed or otherwise stable final path, and `revalidate` for a plain name
whose bytes can change. By default, `immutable` responses are public with a
one-year `max-age`, while `revalidate` responses use `no-cache`; both include a
strong per-file `ETag`. Both serve the bytes read at startup until assets are
loaded again, and both send a precompressed copy stored next to the file, such
as `app-K3M7.js.br`, to a browser that accepts it.

Scalive reads only this format. It does not read a bundler's own metadata, and
it does not generate the manifest, so the build needs a short script that writes
it; [Build a split bundle](#build-a-split-bundle) shows one.

## Add routes and resolve paths {#add-static-routes-and-resolve-paths}

Add @:apiSymbol(val:scalive.StaticAssets.routes)`StaticAssets.routes`@:@ to the
server routes next to the Live routes:

```scala fragment
liveRoutes <- ZioHttp.routes(application, config)
routes = liveRoutes ++ assets.routes
```

The routes serve `GET` and `HEAD` below the configured mount path, and query
strings do not affect lookup.

In a tree, @:apiSymbol(def:scalive.StaticAssets.path)`path`@:@ resolves a
tree-relative name below the asset-set version namespace. With a deployment
manifest, it resolves a logical alias to its final path.

Use @:apiSymbol(def:scalive.StaticAssets.pathOption)`pathOption`@:@ when an
optional asset may be absent. `path` and
@:apiSymbol(def:scalive.StaticAssets.entry)`entry`@:@ throw for a name outside
the loaded tree or manifest. The entry's `cachePolicy` describes its versioned
path or manifest-defined final path, not an optional revalidating original.

@:apiSymbol(class:scalive.StaticAssetCache)`StaticAssetCache`@:@ holds the
`immutable` and `revalidating` response headers. Pass another one as the `cache`
argument of `StaticAssets.tree` or `StaticAssets.manifest` when the application
requires different headers.

## Render tracked tags {#render-tracked-tags}

Render the application's top-level script and stylesheet in the root layout's
`<head>`:

```scala fragment
headTag(
  metaTag(charset := "utf-8"),
  assets.trackedStylesheet("app.css"),
  assets.trackedScript("app.js", defer := true, typ := "text/javascript")
)
```

The tracked helpers
@:apiSymbol(def:scalive.StaticAssets.trackedStylesheet)`StaticAssets.trackedStylesheet`@:@
and @:apiSymbol(def:scalive.StaticAssets.trackedScript)`StaticAssets.trackedScript`@:@
use the versioned or manifest-defined final URL and add `phx-track-static`. The
untracked @:apiSymbol(def:scalive.StaticAssets.stylesheet)`stylesheet`@:@ and
@:apiSymbol(def:scalive.StaticAssets.script)`script`@:@ helpers still use those
URLs but omit that Phoenix marker.

A script that imports chunks is an ES module. Load it with `typ := "module"`
instead; a module script is deferred already.

Keep the top-level script and stylesheet selection explicit in the root layout;
a file being served does not make it a tag. Use tracked helpers for
application bundles whose change should be visible to the LiveView client, and
render them in the root layout: only the root layout's tracked tags are compared.
A connected view reads the tracking result through
[`staticChanged`](client-setup.md#read-connect-metadata).

## Build a split bundle {#build-a-split-bundle}

The [single-file bundle](client-setup.md#build-the-client-bundle) of the client
setup guide is the place to start: one `app.js`, no hashes, loaded as a tree.
Move to a split build when a page should not download a library it does not
use. This documentation site is built that way, and the sources below are the
ones that build it.

### Emit hashed chunks {#emit-hashed-chunks}

Earlier build steps prepare the stylesheets, images and licenses in `build`.
esbuild takes them as entry points next to the script:

@:sourceRegion(documentation/site/assets/build-bundle.mjs, site-split-build)

Three options differ from the single-file build:

- `format: "esm"` with `splitting` makes every dynamic `import()` a chunk of its
  own, and moves code shared between chunks into further chunks.
- `entryNames` and `chunkNames` put a content hash in the name of every file
  esbuild emits, the copied images and licenses included. esbuild writes the
  hashed names into the files that refer to them, which is why Scalive must not
  rename anything afterwards.
- `metafile` records every output, with the source file an entry point or a
  chunk was built from. The script keeps it for the next step.

### Write the manifest {#write-the-manifest}

A second script, run after the bundler, turns the metafile into the
[deployment manifest](#load-a-deployment-manifest). It first decides which
outputs get a stable alias:

@:sourceRegion(documentation/site/assets/write-assets-manifest.mjs, site-manifest-aliases)

Then it lists every output of the metafile as immutable, under its alias when it
has one and under its final path otherwise:

@:sourceRegion(documentation/site/assets/write-assets-manifest.mjs, site-manifest-outputs)

The files esbuild did not emit keep a plain name, so they are listed as
`revalidate`. The site has two, each because something esbuild cannot rewrite
refers to it by name: the search index, which another build task writes to the
same classpath prefix, and a one-line stylesheet through which the diagram SVGs
import the hashed fonts:

@:sourceRegion(documentation/site/assets/write-assets-manifest.mjs, site-manifest-plain-names)

An application whose every file goes through the bundler needs neither. Last,
the script writes the manifest next to the outputs:

@:sourceRegion(documentation/site/assets/write-assets-manifest.mjs, site-manifest-write)

The result lists the entry points by alias and the chunks by path:

```json
{
  "version": 1,
  "assets": {
    "app.css": { "file": "app-JGAYHCDJ.css", "cache": "immutable" },
    "app.js": { "file": "app-2YYIZSDH.js", "cache": "immutable" },
    "chunks/browser-copy-MJP467HH.js": { "file": "chunks/browser-copy-MJP467HH.js", "cache": "immutable" },
    "search-index.json": { "file": "search-index.json", "cache": "revalidate" }
  }
}
```

The build runs both scripts in order and packages the whole output directory,
manifest included, below the `public` classpath prefix, as the
[Mill task](client-setup.md#build-the-client-bundle) for the single-file bundle
does.

### Load the entry points {#load-the-entry-points}

The site loads the manifest from the classpath:

@:sourceRegion(documentation/site/src/scalive/docs/DocumentationSite.scala, site-load-manifest)

Startup then fails if the manifest names a file the build did not package. The
root layout asks for the entry points by alias, the script as a module so that
it can import its chunks:

@:sourceRegion(documentation/site/src/scalive/docs/DocumentationLayouts.scala, site-tracked-entries)

Only the entry points are tags. A chunk is requested by the module that imports
it, at the moment it does. To make a hook's library such a chunk, see
[Load a heavy hook on demand](browser-integration.md#load-a-heavy-hook-on-demand).

An open page can ask for a chunk after a deployment replaced it. What the new
server still serves, and how to keep old chunks available, is covered in
[Deployment](deployment.md#keep-old-files-available).

## Related tasks {#related-tasks}

- Connect the client and build the single-file bundle with [Client setup](client-setup.md#build-the-client-bundle).
- Place bundle tags in the document shell with [Layouts](layouts.md#install-root-and-ordinary-layouts).
- Cache static assets behind a CDN or edge with [Deployment](deployment.md#cache-static-assets).
- Diagnose a stylesheet or script that does not load in [Troubleshooting](troubleshooting.md#diagnose-missing-assets).
