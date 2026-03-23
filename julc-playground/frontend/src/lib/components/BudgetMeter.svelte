<script lang="ts">
  export let cpu: number = 0;
  export let mem: number = 0;

  // Mainnet limits
  const MAX_CPU = 10_000_000_000;
  const MAX_MEM = 14_000_000;

  $: cpuPct = Math.min(100, (cpu / MAX_CPU) * 100);
  $: memPct = Math.min(100, (mem / MAX_MEM) * 100);

  function formatNumber(n: number): string {
    if (n >= 1_000_000_000) return (n / 1_000_000_000).toFixed(2) + 'B';
    if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M';
    if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K';
    return n.toString();
  }

  function barColor(pct: number): string {
    if (pct < 50) return 'var(--success)';
    if (pct < 80) return 'var(--warning)';
    return 'var(--error)';
  }
</script>

<div class="budget-meter">
  <div class="meter-row">
    <span class="meter-label">CPU</span>
    <div class="meter-bar">
      <div class="meter-fill" style="width: {cpuPct}%; background: {barColor(cpuPct)}"></div>
    </div>
    <span class="meter-value">{formatNumber(cpu)} / {formatNumber(MAX_CPU)}</span>
    <span class="meter-pct">{cpuPct.toFixed(1)}%</span>
  </div>
  <div class="meter-row">
    <span class="meter-label">MEM</span>
    <div class="meter-bar">
      <div class="meter-fill" style="width: {memPct}%; background: {barColor(memPct)}"></div>
    </div>
    <span class="meter-value">{formatNumber(mem)} / {formatNumber(MAX_MEM)}</span>
    <span class="meter-pct">{memPct.toFixed(1)}%</span>
  </div>
</div>

<style>
  .budget-meter {
    display: flex;
    flex-direction: column;
    gap: 6px;
    padding: 8px 0;
  }

  .meter-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .meter-label {
    font-size: 11px;
    font-weight: 600;
    color: var(--text-secondary);
    width: 30px;
    font-family: var(--font-mono);
  }

  .meter-bar {
    flex: 1;
    height: 8px;
    background: var(--bg-secondary);
    border-radius: 4px;
    overflow: hidden;
  }

  .meter-fill {
    height: 100%;
    border-radius: 4px;
    transition: width 0.3s ease;
  }

  .meter-value {
    font-size: 10px;
    color: var(--text-muted);
    font-family: var(--font-mono);
    min-width: 100px;
    text-align: right;
  }

  .meter-pct {
    font-size: 10px;
    color: var(--text-secondary);
    font-family: var(--font-mono);
    min-width: 40px;
    text-align: right;
  }
</style>
