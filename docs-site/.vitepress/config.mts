// The site is built from the Markdown under ../docs, which stays the single source of the
// documentation: nothing is copied here, and the rule pages in the sidebar are discovered from
// the directory tree at build time so that adding a rule page is enough to publish it.
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'

const docs = fileURLToPath(new URL('../../docs', import.meta.url))

/** The first `# ` heading of a Markdown file, or its file name. */
function titleOf(file: string): string {
  const heading = readFileSync(file, 'utf8')
    .split('\n')
    .find((line) => line.startsWith('# '))
  return heading ? heading.slice(2).trim() : file
}

/** Sidebar items for every Markdown page directly under `dir`, except the index pages. */
function pages(dir: string, exclude: string[] = ['README.md', 'index.md']) {
  return readdirSync(join(docs, dir))
    .filter((name) => name.endsWith('.md') && !exclude.includes(name))
    .sort()
    .map((name) => ({
      text: titleOf(join(docs, dir, name)),
      link: `/${dir}/${name.replace(/\.md$/, '')}`,
    }))
}

export default defineConfig({
  title: 'Kotrail',
  description:
    'A flexible set of compiler checker rules that keep your Kotlin code durable when developing with AI.',
  srcDir: docs,
  base: '/kotrail/',
  cleanUrls: true,
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: '/kotrail/favicon.svg' }]],
  vite: {
    resolve: {
      // Pages live outside this directory, so Vite would look for `vue` next to them and fail;
      // point every `vue` import at the site's own node_modules instead.
      alias: [
        {
          find: /^vue(\/.*)?$/,
          replacement: fileURLToPath(new URL('../node_modules/vue$1', import.meta.url)),
        },
      ],
    },
  },
  themeConfig: {
    logo: { light: '/kotrail-mark.svg', dark: '/kotrail-mark-dark.svg' },
    nav: [
      { text: 'Guide', link: '/getting-started' },
      { text: 'Rules', link: '/rules/README' },
      { text: 'Configuration', link: '/configuration' },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Getting started', link: '/getting-started' },
          { text: 'The Gradle plugin', link: '/gradle-plugin' },
          { text: 'Configuration', link: '/configuration' },
          { text: 'Supported Kotlin versions', link: '/supported-kotlin-versions' },
          { text: 'Inferred metadata', link: '/inferred-metadata' },
          { text: 'Publishing', link: '/publishing' },
        ],
      },
      {
        text: 'Rules',
        items: [{ text: 'Overview', link: '/rules/README' }, ...pages('rules')],
      },
      { text: 'Compose rules', items: pages('rules/compose') },
      { text: 'Test rules', items: pages('rules/test') },
      { text: 'Kotlin/Native rules', items: pages('rules/native') },
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/kitakkun/kotrail' }],
    search: { provider: 'local' },
    outline: [2, 3],
  },
})
