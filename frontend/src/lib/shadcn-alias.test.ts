import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { cn } from '@/lib/utils'
import { buttonVariants } from '@/components/ui/button'

const componentsConfig = JSON.parse(
  readFileSync(join(process.cwd(), 'components.json'), 'utf8'),
) as { aliases: Record<string, string> }
const typescriptConfig = JSON.parse(
  readFileSync(join(process.cwd(), 'tsconfig.json'), 'utf8'),
) as { compilerOptions: { paths: Record<string, string[]> } }

describe('shadcn import aliases', () => {
  it('keeps configured utility and UI aliases resolvable', () => {
    expect(componentsConfig.aliases.utils).toBe('@/lib/utils')
    expect(componentsConfig.aliases.ui).toBe('@/components/ui')
    expect(typescriptConfig.compilerOptions.paths['@/*']).toEqual(['./src/*'])
    expect(cn('px-2', 'px-4')).toContain('px-4')
    expect(buttonVariants()).toContain('inline-flex')
  })
})
