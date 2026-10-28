import { createHash, createPublicKey, verify } from 'node:crypto'
import { readFileSync } from 'node:fs'

const name = 'semver'
const version = '6.3.1'
const expectedIntegrity = 'sha512-BR7VvDCVHO+q2xBEWskxS6DJE1qRnb7DxzUrogb71CWoSficBxYsiAGd+Kl0mmq/MprG9yArRkyrQxTO6XjMzA=='
const lockfile = readFileSync(process.argv[2] ?? 'frontend/pnpm-lock.yaml', 'utf8')
const entry = lockfile.match(/\n  semver@6\.3\.1:\r?\n    resolution: \{integrity: ([^}]+)\}/)
if (entry?.[1] !== expectedIntegrity) throw new Error('The reviewed semver exception has changed; review its policy before installation')

async function json(url) {
  const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(15_000) })
  if (!response.ok) throw new Error(`npm metadata request failed: HTTP ${response.status}`)
  return response.json()
}

const metadata = await json(`https://registry.npmjs.org/${name}/${version}`)
if (metadata.dist.integrity !== expectedIntegrity) throw new Error('npm metadata differs from the reviewed integrity')
const keys = (await json('https://registry.npmjs.org/-/npm/v1/keys')).keys
const published = new Date('2023-07-10T22:38:41.428Z')
const valid = metadata.dist.signatures.some(signature => {
  const key = keys.find(candidate => candidate.keyid === signature.keyid)
  if (!key || (key.expires && published >= new Date(key.expires))) return false
  const publicKey = createPublicKey({ key: Buffer.from(key.key, 'base64'), type: 'spki', format: 'der' })
  return verify('sha256', Buffer.from(`${name}@${version}:${expectedIntegrity}`), publicKey,
    Buffer.from(signature.sig, 'base64'))
})
if (!valid) throw new Error('The reviewed semver registry signature is invalid')

const url = new URL(metadata.dist.tarball)
if (url.protocol !== 'https:' || url.hostname !== 'registry.npmjs.org') throw new Error('Unexpected npm artifact source')
const response = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(15_000) })
if (!response.ok) throw new Error('npm artifact download failed')
const archive = Buffer.from(await response.arrayBuffer())
if (`sha512-${createHash('sha512').update(archive).digest('base64')}` !== expectedIntegrity) {
  throw new Error('The reviewed semver artifact failed SHA-512 verification')
}
console.log('Verified historical semver exception: npm registry signature and pinned SHA-512')
