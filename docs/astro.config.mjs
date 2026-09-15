// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import svelte from '@astrojs/svelte';
import tailwindcss from '@tailwindcss/vite';
import llmsIntegration from './scripts/llms-integration.mjs';

// The playground is a standalone static app copied into public/playground/ (see julc-playground/BUILD_FROM_SOURCE.md).
// GitHub Pages serves /playground/ as index.html; the dev server does not, so rewrite it during `astro dev`.
const playgroundDevIndex = {
  name: 'julc-playground-dev-index',
  configureServer(server) {
    server.middlewares.use((req, res, next) => {
      const [path, query] = (req.url ?? '').split('?');
      const suffix = query ? `?${query}` : '';
      if (path === '/playground') {
        // The app uses relative asset paths, so it must be loaded from the directory URL.
        res.statusCode = 302;
        res.setHeader('Location', `/playground/${suffix}`);
        res.end();
        return;
      }
      if (path === '/playground/') req.url = `/playground/index.html${suffix}`;
      next();
    });
  },
};

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
        { label: 'Playground', link: '/playground/', badge: { text: 'Browser', variant: 'tip' } },
        {
          label: 'AI Agents',
          items: [
            { label: 'Using JuLC with AI', slug: 'ai' },
            { label: 'AI Starter Pack', slug: 'ai/starter-pack' },
          ],
        },
        {
          label: 'Guides',
          items: [
            { label: 'Advanced Guide', slug: 'guides/advanced-guide' },
            { label: 'For-Loop Patterns', slug: 'guides/for-loop-patterns' },
            { label: 'Testing Guide', slug: 'guides/testing-guide' },
            { label: 'Source Maps', slug: 'guides/source-maps' },
            { label: 'Strict Data Boundaries', slug: 'guides/strict-data-boundaries' },
            { label: 'Multi-validator Blueprints', slug: 'guides/purpose-indexed-blueprints' },
          ],
        },
        {
          label: 'Formal Verification',
          badge: { text: 'Experimental', variant: 'caution' },
          items: [
            { label: 'Overview', slug: 'guides/formal-verification' },
            { label: 'Design', slug: 'guides/formal-verification/design' },
            { label: 'Annotation Profiles', slug: 'guides/formal-verification/annotation-profiles' },
            { label: 'Typed Java DSL', slug: 'guides/formal-verification/typed-dsl' },
            { label: 'DSL Examples', slug: 'guides/formal-verification/dsl-examples' },
            { label: 'API & DSL Reference', slug: 'guides/formal-verification/api-reference' },
            { label: 'Troubleshooting', slug: 'guides/formal-verification/troubleshooting' },
          ],
        },
        {
          label: 'Best Practices',
          items: [
            { label: 'Conditionals and Script Size', slug: 'best-practices/conditionals' },
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
            { label: 'Release Notes', slug: 'reference/release-notes' },
            { label: 'Cost Model Profiles', slug: 'reference/cost-model-profiles' },
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
            { label: 'Java to UPLC End to End', slug: 'internals/java-to-uplc-end-to-end' },
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
    llmsIntegration(),
  ],
  vite: {
    plugins: [tailwindcss(), playgroundDevIndex],
  },
});
