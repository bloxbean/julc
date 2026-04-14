<script>
  let visible = $state(false)

  const stats = [
    { value: 3400, suffix: '+', label: 'Tests Passing' },
    { value: 13, suffix: '', label: 'Stdlib Libraries' },
    { value: 29, suffix: '', label: 'Modules' },
    { value: 20, suffix: 'M', prefix: '~', label: 'CPU / Request' },
  ]

  let counters = $state(stats.map(() => 0))

  function observe(node) {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          visible = true
          animateCounters()
          observer.disconnect()
        }
      },
      { threshold: 0.3 }
    )
    observer.observe(node)
    return { destroy: () => observer.disconnect() }
  }

  function animateCounters() {
    const duration = 1500
    const start = performance.now()

    function tick(now) {
      const elapsed = now - start
      const progress = Math.min(elapsed / duration, 1)
      const eased = 1 - Math.pow(1 - progress, 3) // ease-out cubic

      counters = stats.map((s) => Math.floor(eased * s.value))

      if (progress < 1) {
        requestAnimationFrame(tick)
      }
    }

    requestAnimationFrame(tick)
  }
</script>

<div use:observe class="grid grid-cols-2 lg:grid-cols-4 gap-6">
  {#each stats as stat, i}
    <div class="text-center p-6 rounded-xl border border-white/5 bg-white/[0.02]">
      <div class="text-3xl sm:text-4xl font-bold text-white" style="font-family: var(--font-mono)">
        {stat.prefix || ''}<span class="text-[#D4600E]">{counters[i].toLocaleString()}</span>{stat.suffix}
      </div>
      <div class="mt-2 text-sm text-[#a1a1aa]">{stat.label}</div>
    </div>
  {/each}
</div>
