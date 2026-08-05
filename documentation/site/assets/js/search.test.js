import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

import { aliases, nextActiveIndex, search, terms } from "./search.js"

const fixtureUrl = new URL("../../../model/test/resources/search-ranking.json", import.meta.url)
const fixture = JSON.parse(await readFile(fixtureUrl, "utf8"))

test("matches the shared deterministic ranking contract", () => {
  for (const rankingCase of fixture.cases) {
    const actual = search(rankingCase.query, fixture.entries, rankingCase.limit).map(
      (entry) => entry.id,
    )
    assert.deepEqual(actual, rankingCase.ids, rankingCase.query)
  }
})

test("preserves Scala identifiers and derives aliases", () => {
  assert.deepEqual(terms("scalive.LiveView"), ["scalive.liveview"])
  assert.deepEqual(terms("LiveView handleMessage"), ["liveview", "handlemessage"])
  const values = aliases("scalive.LiveView.handleMessage")
  assert.ok(values.includes("scalive.liveview.handlemessage"))
  assert.ok(values.includes("liveview"))
  assert.ok(values.includes("handle"))
  assert.ok(values.includes("message"))
})

test("wraps keyboard selection deterministically", () => {
  assert.equal(nextActiveIndex(-1, "ArrowDown", 3), 0)
  assert.equal(nextActiveIndex(2, "ArrowDown", 3), 0)
  assert.equal(nextActiveIndex(0, "ArrowUp", 3), 2)
  assert.equal(nextActiveIndex(1, "Escape", 3), 1)
  assert.equal(nextActiveIndex(0, "ArrowDown", 0), -1)
})
