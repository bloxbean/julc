<script lang="ts">
  import { onMount } from 'svelte';
  import { api, type ExampleItem } from '../api/client';

  export let onSelect: (source: string) => void;

  let examples: ExampleItem[] = [];
  let selected = '';

  onMount(async () => {
    try {
      examples = await api.examples();
    } catch {
      examples = [];
    }
  });

  async function handleChange() {
    if (!selected) return;
    const ex = examples.find(e => e.name === selected);
    if (ex) {
      onSelect(ex.source);
    }
  }
</script>

<div class="example-picker">
  <select bind:value={selected} on:change={handleChange}>
    <option value="">Load example...</option>
    {#each examples as ex}
      <option value={ex.name}>{ex.name.replace('.jrl', '')}</option>
    {/each}
  </select>
</div>

<style>
  .example-picker select {
    font-size: 12px;
  }
</style>
