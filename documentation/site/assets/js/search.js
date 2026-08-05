const kindOrder = {
  page: 0,
  heading: 1,
  example: 2,
  apiSymbol: 3,
  compatibility: 4,
}

function asciiLower(value) {
  return value.replace(/[A-Z]/g, (character) =>
    String.fromCharCode(character.charCodeAt(0) + 32),
  )
}

function identifierTokens(value) {
  return value.match(/[A-Za-z0-9_.$]+/g) ?? []
}

function camelWords(value) {
  const words = []
  let current = ""

  for (let index = 0; index < value.length; index += 1) {
    const character = value[index]
    const previous = index > 0 ? value[index - 1] : undefined
    const next = index + 1 < value.length ? value[index + 1] : undefined
    const boundary =
      /[A-Z]/.test(character) &&
      current.length > 0 &&
      ((previous !== undefined && /[a-z0-9]/.test(previous)) ||
        (next !== undefined && /[a-z]/.test(next)))

    if (boundary) {
      words.push(asciiLower(current))
      current = ""
    }
    current += character
  }

  if (current.length > 0) words.push(asciiLower(current))
  return words
}

export function terms(value) {
  return [...new Set(identifierTokens(value).map(asciiLower).filter(Boolean))]
}

export function aliases(value) {
  const values = identifierTokens(value).flatMap((token) => {
    const segments = token.split(/[._$]/).filter(Boolean)
    return [
      asciiLower(token),
      ...segments.flatMap((segment) => [asciiLower(segment), ...camelWords(segment)]),
    ]
  })
  return [...new Set(values.filter(Boolean))]
}

function normalizedText(value) {
  return asciiLower(value).split(/\s+/).filter(Boolean).join(" ")
}

function fieldScore(term, normalized, values, exact, prefix, contains) {
  if (values.includes(term)) return exact
  if (values.some((value) => value.startsWith(term))) return prefix
  if (normalized.includes(term)) return contains
  return 0
}

function score(entry, query, queryTerms) {
  const title = normalizedText(entry.title)
  const description = normalizedText(entry.description)
  const text = normalizedText(entry.text)
  const titleTerms = aliases(entry.title)
  const descriptionTerms = aliases(entry.description)
  const textTerms = aliases(entry.text)

  const scores = queryTerms.map((term) =>
    Math.max(
      fieldScore(term, title, titleTerms, 800, 600, 400),
      fieldScore(term, description, descriptionTerms, 200, 150, 100),
      fieldScore(term, text, textTerms, 100, 75, 50),
    ),
  )
  if (scores.some((value) => value === 0)) return undefined

  const queryText = queryTerms.join(" ")
  const phraseScore =
    title === queryText ? 5000 : title.startsWith(queryText) ? 2500 : title.includes(queryText) ? 1000 : 0
  const trimmedQuery = query.trim()
  const caseScore =
    entry.title === trimmedQuery || entry.title.endsWith(`.${trimmedQuery}`) ? 1500 : 0
  return phraseScore + caseScore + scores.reduce((total, value) => total + value, 0)
}

function compareText(left, right) {
  if (left < right) return -1
  if (left > right) return 1
  return 0
}

export function search(query, entries, limit = 20) {
  const queryTerms = terms(query)
  if (queryTerms.length === 0 || limit <= 0) return []

  return entries
    .flatMap((entry) => {
      const entryScore = score(entry, query, queryTerms)
      return entryScore === undefined ? [] : [{ entry, score: entryScore }]
    })
    .sort((left, right) => {
      if (left.score !== right.score) return right.score - left.score
      const leftKind = kindOrder[left.entry.kind] ?? Number.MAX_SAFE_INTEGER
      const rightKind = kindOrder[right.entry.kind] ?? Number.MAX_SAFE_INTEGER
      if (leftKind !== rightKind) return leftKind - rightKind
      return (
        compareText(asciiLower(left.entry.title), asciiLower(right.entry.title)) ||
        compareText(left.entry.route, right.entry.route) ||
        compareText(left.entry.fragment ?? "", right.entry.fragment ?? "") ||
        compareText(left.entry.id, right.entry.id)
      )
    })
    .slice(0, limit)
    .map(({ entry }) => entry)
}

export function nextActiveIndex(current, key, count) {
  if (count <= 0) return -1
  if (key === "ArrowDown") return current >= count - 1 ? 0 : current + 1
  if (key === "ArrowUp") return current <= 0 ? count - 1 : current - 1
  return current
}
