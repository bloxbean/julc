<script lang="ts">
  import { onMount } from 'svelte';
  import { api, type ExampleItem } from '../api/client';

  export let onSelect: (source: string) => void;

  let allExamples: ExampleItem[] = [];
  let selected = '';
  let loadError = false;

  onMount(async () => {
    try {
      allExamples = await api.examples();
    } catch {
      allExamples = [];
      loadError = true;
    }
  });

  async function handleChange() {
    if (!selected) return;
    const ex = allExamples.find(e => e.name === selected);
    if (ex) {
      onSelect(ex.source);
    }
  }

  function displayName(name: string): string {
    return name.replace('.java', '');
  }
</script>

<div class="example-picker">
  {#if loadError}
    <span class="load-error">Failed to load examples</span>
  {:else}
    <select bind:value={selected} on:change={handleChange}>
      <option value="">Load example...</option>
      {#each allExamples as ex}
        <option value={ex.name}>{displayName(ex.name)}</option>
      {/each}
    </select>
  {/if}
</div>

<style>
  .example-picker select {
    font-size: 12px;
  }

  .load-error {
    font-size: 11px;
    color: var(--error);
  }
</style>
