<script>
  let visible = $state(false)

  const benchmarks = [
    { name: 'JuLC', value: 20, max: 723, color: '#D4600E', highlight: true, label: '~20M' },
    { name: 'Plutarch', value: 121, max: 723, color: '#6b7280', highlight: false, label: '121M' },
    { name: 'Aiken', value: 164, max: 723, color: '#6b7280', highlight: false, label: '164M' },
    { name: 'Plinth', value: 723, max: 723, color: '#6b7280', highlight: false, label: '723M' },
  ]

  function observe(node) {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          visible = true
          observer.disconnect()
        }
      },
      { threshold: 0.3 }
    )
    observer.observe(node)
    return { destroy: () => observer.disconnect() }
  }
</script>

<div use:observe class="max-w-2xl mx-auto">
  <div class="space-y-5">
    {#each benchmarks as bench, i}
      <div class="flex items-center gap-4">
        <span
          class="w-20 text-right text-sm font-medium shrink-0 {bench.highlight ? 'text-[#D4600E] font-bold' : 'text-[#a1a1aa]'}"
          style="font-family: var(--font-mono)"
        >
          {bench.name}
        </span>
        <div class="flex-1 relative h-9 rounded-lg bg-white/5 overflow-hidden">
          <div
            class="absolute inset-y-0 left-0 rounded-lg transition-all duration-1000 ease-out flex items-center justify-end pr-3"
            style="width: {visible ? (bench.value / bench.max * 100) + '%' : '0%'}; background: {bench.highlight ? bench.color : bench.color}; opacity: {bench.highlight ? 1 : 0.4}; transition-delay: {i * 150}ms; {bench.highlight ? 'box-shadow: 0 0 20px rgba(212, 96, 14, 0.3)' : ''}"
          >
            <span
              class="text-xs font-semibold text-white whitespace-nowrap {visible ? 'opacity-100' : 'opacity-0'} transition-opacity duration-300"
              style="transition-delay: {i * 150 + 600}ms; font-family: var(--font-mono)"
            >
              {bench.label}
            </span>
          </div>
        </div>
      </div>
    {/each}
  </div>
  <p class="mt-6 text-center text-xs text-[#6b7280]">
    CPU budget per request (lower is better) &mdash; WingRiders DEX benchmark
  </p>
</div>
