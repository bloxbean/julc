// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import svelte from '@astrojs/svelte';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  site: 'https://julc.dev',
  integrations: [
    starlight({
      title: 'JuLC',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/bloxbean/julc' },
      ],
      editLink: {
        baseUrl: 'https://github.com/bloxbean/julc/edit/main/docs/',
      },
      components: {
        Header: './src/components/overrides/Header.astro',
        Head: './src/components/overrides/Head.astro',
      },
      customCss: ['./src/styles/starlight.css'],
      sidebar: [
        { label: 'Overview', slug: 'overview' },
        { label: 'Write Your First Contract', slug: 'first-contract' },
        { label: 'Getting Started', slug: 'getting-started' },
        {
          label: 'Guides',
          items: [
            { label: 'Advanced Guide', slug: 'guides/advanced-guide' },
            { label: 'For-Loop Patterns', slug: 'guides/for-loop-patterns' },
            { label: 'Testing Guide', slug: 'guides/testing-guide' },
            { label: 'Source Maps', slug: 'guides/source-maps' },
          ],
        },
        {
          label: 'Standard Library',
          items: [
            { label: 'Library Reference', slug: 'stdlib/stdlib-guide' },
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'API Reference', slug: 'reference/api-reference' },
            { label: 'Library Developer Guide', slug: 'reference/library-developer-guide' },
            { label: 'Examples', slug: 'reference/examples' },
            { label: 'Troubleshooting', slug: 'reference/troubleshooting' },
          ],
        },
        {
          label: 'Internals',
          collapsed: true,
          items: [
            { label: 'Compiler Design', slug: 'internals/compiler-design' },
            { label: 'Compiler Developer Guide', slug: 'internals/compiler-developer-guide' },
          ],
        },
        {
          label: 'Experimental',
          collapsed: true,
          items: [
            { label: 'JRL Guide', slug: 'experimental/jrl-guide', badge: { text: 'Experimental', variant: 'caution' } },
          ],
        },
      ],
    }),
    svelte(),
  ],
  vite: {
    plugins: [tailwindcss()],
  },
});
