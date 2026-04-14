/**
 * One-time script: transforms markdown files from docs-md-tmp/ into
 * Starlight-compatible format in src/content/docs/.
 *
 * - Adds Starlight frontmatter (title, description)
 * - Strips ## Table of Contents sections
 * - Removes the first # Heading (Starlight renders title from frontmatter)
 * - Rewrites internal cross-doc links for new directory structure
 */
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const SRC = join(__dirname, '..', '..', 'docs-md-tmp')
const DEST = join(__dirname, '..', 'src', 'content', 'docs')

// Map: old filename -> new relative path (within src/content/docs/)
const FILE_MAP = {
  'getting-started.md':          'getting-started.md',
  'advanced-guide.md':           'guides/advanced-guide.md',
  'for-loop-patterns.md':        'guides/for-loop-patterns.md',
  'testing-guide.md':            'guides/testing-guide.md',
  'source-maps.md':              'guides/source-maps.md',
  'stdlib-guide.md':             'stdlib/stdlib-guide.md',
  'api-reference.md':            'reference/api-reference.md',
  'library-developer-guide.md':  'reference/library-developer-guide.md',
  'troubleshooting.md':          'reference/troubleshooting.md',
  'compiler-design.md':          'internals/compiler-design.md',
  'compiler-developer-guide.md': 'internals/compiler-developer-guide.md',
  'jrl-guide.md':                'experimental/jrl-guide.md',
}

// Link rewrite map: old filename (without .md) -> new Starlight path
const LINK_MAP = {
  'getting-started':          '/getting-started/',
  'advanced-guide':           '/guides/advanced-guide/',
  'for-loop-patterns':        '/guides/for-loop-patterns/',
  'testing-guide':            '/guides/testing-guide/',
  'source-maps':              '/guides/source-maps/',
  'stdlib-guide':             '/stdlib/stdlib-guide/',
  'api-reference':            '/reference/api-reference/',
  'library-developer-guide':  '/reference/library-developer-guide/',
  'troubleshooting':          '/reference/troubleshooting/',
  'compiler-design':          '/internals/compiler-design/',
  'compiler-developer-guide': '/internals/compiler-developer-guide/',
  'jrl-guide':                '/experimental/jrl-guide/',
}

for (const [oldFile, newPath] of Object.entries(FILE_MAP)) {
  const srcPath = join(SRC, oldFile)
  let content = readFileSync(srcPath, 'utf-8')

  // Extract title from first # heading
  const titleMatch = content.match(/^#\s+(.+)$/m)
  const title = titleMatch ? titleMatch[1].trim() : oldFile.replace('.md', '')

  // Remove first # heading line
  content = content.replace(/^#\s+.+\n+/, '')

  // Strip ## Table of Contents sections (from heading to next ## or end of section)
  content = content.replace(
    /## Table of Contents\s*\n[\s\S]*?(?=\n## )/g,
    ''
  )

  // Rewrite cross-doc links: [text](file.md) or [text](file.md#anchor)
  content = content.replace(
    /\]\(([a-zA-Z0-9_-]+)\.md(#[^)]*?)?\)/g,
    (match, name, anchor) => {
      const newLink = LINK_MAP[name]
      if (newLink) {
        return `](${newLink}${anchor || ''})`
      }
      return match // leave unknown links unchanged
    }
  )

  // Build Starlight frontmatter
  const escapedTitle = title.replace(/"/g, '\\"')
  const frontMatter = `---\ntitle: "${escapedTitle}"\ndescription: "${escapedTitle} - JuLC documentation"\n---\n\n`

  const output = frontMatter + content
  const destPath = join(DEST, newPath)

  // Ensure directory exists
  mkdirSync(dirname(destPath), { recursive: true })
  writeFileSync(destPath, output, 'utf-8')
  console.log(`  ${oldFile} -> ${newPath}`)
}

console.log(`\nDone: ${Object.keys(FILE_MAP).length} files transformed.`)
