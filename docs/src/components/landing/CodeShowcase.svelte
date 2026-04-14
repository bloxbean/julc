<script>
  let { tabs = [] } = $props()
  let activeTab = $state(0)
</script>

<div>
  <!-- Tabs -->
  <div class="flex gap-1 mb-4">
    {#each tabs as tab, i}
      <button
        onclick={() => activeTab = i}
        class="px-4 py-2 rounded-lg text-sm font-medium transition-all duration-200 {activeTab === i
          ? 'bg-[#D4600E] text-white shadow-lg shadow-[#D4600E]/20'
          : 'text-[#a1a1aa] hover:text-white hover:bg-white/5'}"
      >
        {tab.label}
      </button>
    {/each}
  </div>

  <!-- Code block -->
  <div class="overflow-hidden rounded-xl border border-white/5 bg-[#0d1117]">
    <div class="flex items-center gap-2 border-b border-white/5 px-4 py-2.5">
      <div class="h-2.5 w-2.5 rounded-full bg-red-500/50"></div>
      <div class="h-2.5 w-2.5 rounded-full bg-yellow-500/50"></div>
      <div class="h-2.5 w-2.5 rounded-full bg-green-500/50"></div>
      <span class="ml-2 text-xs text-[#6b7280]" style="font-family: var(--font-mono)">
        {tabs[activeTab]?.filename}
      </span>
    </div>
    {#key activeTab}
      <div class="code-container overflow-x-auto animate-fade-in">
        {@html tabs[activeTab]?.html}
      </div>
    {/key}
  </div>
</div>

<style>
  @keyframes fadeIn {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: translateY(0); }
  }
  .animate-fade-in {
    animation: fadeIn 0.25s ease-out;
  }
  /* Override Shiki's generated pre/code styles to match our design */
  .code-container :global(pre) {
    margin: 0;
    padding: 1.25rem;
    background: transparent !important;
    font-size: 13px;
    line-height: 1.7;
    font-family: 'JetBrains Mono', 'SF Mono', 'Fira Code', 'Courier New', monospace;
  }
  .code-container :global(code) {
    font-family: inherit;
  }
</style>
