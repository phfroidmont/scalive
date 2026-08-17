{%
title = "Why I Built Scalive"
description = "The path from building a production Scala.js application with Laminar to bringing Phoenix LiveView's server-driven model to Scala."
order = 1
section = project
%}

## Choosing A Stack {#choosing-a-stack}

In 2021, I started working full time on my own product,
[OsteoView](https://osteoview.app/). The backend was always going to be
written in Scala. I loved the language and wanted its type system and
expressiveness at the center of the application. The frontend choice was much
less obvious.

I had already worked with several generations of frontend technology, from
old-school JSP and jQuery applications to Angular. Those experiences left me
convinced that JavaScript is useful for small pieces of browser-side behavior
but a poor foundation for large applications. I found JavaScript codebases
fragile and unnecessarily difficult to maintain at scale. TypeScript catches
more mistakes, but retains JavaScript's runtime semantics and much of its
ecosystem and tooling complexity. I did not want to build another product's
frontend on that foundation if I could avoid it.

Finding Scala.js opened another possibility: perhaps I could use Scala across
the whole application.

I explored several ways to use mainstream JavaScript libraries from Scala.js,
but none felt ergonomic enough. The interop worked; the resulting programming
model did not feel like Scala.

## Discovering Laminar {#discovering-laminar}

Nikita Gazarov's article,
[My Four Year Quest For Perfect Scala.js UI Development](https://dev.to/raquo/my-four-year-quest-for-perfect-scala-js-ui-development-b9a),
led me to [Laminar](https://laminar.dev/). It changed my experience of frontend
development completely.

Laminar was a revelation. For the first time in years, I genuinely enjoyed
building a frontend. Its programming model let me assemble rich, efficient
interfaces from small composable building blocks while retaining the benefits
of Scala's type system.

I built the entire OsteoView frontend with Laminar and ran it in production for
several years. It proved remarkably reliable and easy to maintain. Laminar
remains an exceptional library, and it would still be my first choice today for
a product that truly requires a single-page application.

## Questioning The SPA Boundary {#questioning-the-spa-boundary}

Over time, however, I became dissatisfied with the SPA model itself. OsteoView's
all-Scala stack allowed the frontend and backend to share code, while
[Tapir](https://tapir.softwaremill.com/) gave them a type-safe API contract. Even
with those advantages, application state still had to be coordinated across two
runtimes. Too much of my time went into keeping the browser and server
synchronized rather than building the product. Client bundle size became
another cost I no longer wanted to accept by default.

I began exploring server-driven approaches, most notably
[htmx](https://htmx.org/). I admired its grounding in the basic mechanics of the
web and how much synchronization work it avoided. For the rich interfaces I
wanted to build, though, I still found the resulting application code more
tedious than I wanted.

## Revisiting Phoenix LiveView {#revisiting-phoenix-liveview}

Eventually I returned to Phoenix LiveView. I had looked at it when it was still
a very new idea, but had not pursued it because Elixir is dynamically typed.
Elixir is a very nice language, but I'm a types kind of guy, and that tradeoff
was not right for me.

When [Phoenix LiveView 1.1 was released](https://www.phoenixframework.org/blog/phoenix-liveview-1-1-released),
its introduction of keyed comprehensions caught my attention. The idea reminded
me of Laminar's `split` operator, which I already knew as an effective way to
render changing collections efficiently. That resemblance prompted me to look
more closely at how LiveView represented and transmitted diffs.

What I found was a model that directly addressed my frustrations with modern web
development: the server owns the application state, events travel over a
persistent connection, and the browser receives efficient DOM updates without
becoming a second application that must be kept in sync.

Then the idea behind Scalive became obvious: what if I could combine the type
safety and expressiveness of Scala with the server-driven model of Phoenix
LiveView?

## From Prototype To Scalive {#from-prototype-to-scalive}

The first basic prototype established the most important technical premise:
reusing the Phoenix LiveView JavaScript client from a Scala server was viable.

The harder design question was how HTML templates should identify their dynamic
parts so that Scalive could produce efficient LiveView diffs. I ended up using
an abstraction similar to Laminar's `Signal` to stage those parts. That supported
the protocol's distinction between static and dynamic content without forcing
an awkward or unidiomatic API on users.

Many iterations followed. I eventually settled on an API that builds on the
strengths of Scala 3 rather than reproducing Elixir APIs literally. Typed models,
typed messages, ZIO effects, and typed HTML form the programming model I had
wanted: ergonomic and productive without giving up the guarantees that drew me
to Scala in the first place.

Scalive grew from that journey. It preserves what makes Phoenix LiveView's
server-driven architecture compelling while using Scala to push type safety,
robustness, and expressiveness further.

## Thanks {#thanks}

I must thank [Chris McCord](https://github.com/chrismccord) for creating Phoenix
LiveView and developing it into the framework that inspired Scalive. I am also
grateful to the contributors who helped build and refine it. Chris's articles
communicated its ideas with unusual clarity and prompted me to explore its
architecture more deeply.

I must also thank [Nikita Gazarov](https://github.com/raquo), not only for Laminar
and all the inspiring discussions around it, but also for creating
[Scala DOM Types](https://github.com/raquo/scala-dom-types). That project made it
possible to build Scalive's well-documented HTML DSL on a comprehensive,
carefully designed representation of the web platform.

Laminar showed me how good frontend development could be in Scala. Phoenix
LiveView showed me how much of an interactive application could remain on the
server. Scalive would not exist without either body of work.
