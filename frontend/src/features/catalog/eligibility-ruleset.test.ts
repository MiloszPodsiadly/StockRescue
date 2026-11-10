import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, it } from 'vitest'
import { allowedAssessmentNotice, platformRulesetDigest, platformRulesetId } from './eligibility-ruleset'

it('keeps the pinned browser ruleset identity, digest and notice synchronized with the backend', () => {
  const javaRuleset = readFileSync(resolve(process.cwd(), '../backend/src/main/java/com/stockrescue/catalog/application/FoodSafetyRuleset.java'), 'utf8')
  const javaEvaluation = readFileSync(resolve(process.cwd(), '../backend/src/main/java/com/stockrescue/catalog/application/FoodSafetyEvaluation.java'), 'utf8')
  const backendId = javaRuleset.match(/CURRENT_ID\s*=\s*"([^"]+)"/)?.[1]
  const manifestBlock = javaRuleset.match(/CURRENT_MANIFEST\s*=\s*String\.join\("\\n",([\s\S]*?)\);/)?.[1]
  const manifestLines = manifestBlock ? Array.from(manifestBlock.matchAll(/"([^"]*)"/g), match => match[1]) : []
  const backendDigest = createHash('sha256').update(manifestLines.join('\n'), 'utf8').digest('hex')
  const backendNotice = javaEvaluation.match(/ALLOWED_NOTICE\s*=\s*"([^"]+)"/)?.[1]

  expect(backendId).toBe(platformRulesetId)
  expect(backendDigest).toBe(platformRulesetDigest)
  expect(backendNotice).toBe(allowedAssessmentNotice)
})
