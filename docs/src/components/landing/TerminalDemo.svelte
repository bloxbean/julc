<script>
  let visible = $state(false)
  let displayLines = $state([])
  let cursorVisible = $state(true)

  const lines = [
    { type: 'prompt', text: '$ julc repl' },
    { type: 'system', text: 'JuLC REPL v0.1.0 \u2014 type :help for commands' },
    { type: 'blank', text: '' },
    { type: 'prompt', text: 'julc> 1 + 2' },
    { type: 'result', text: '=> 3' },
    { type: 'budget', text: '   CPU: 230,100  Mem: 602' },
    { type: 'blank', text: '' },
    { type: 'prompt', text: 'julc> ListsLib.length(List.of(1, 2, 3))' },
    { type: 'result', text: '=> 3' },
    { type: 'budget', text: '   CPU: 1,082,720  Mem: 3,410' },
    { type: 'blank', text: '' },
    { type: 'prompt', text: 'julc> MathLib.pow(2, 10)' },
    { type: 'result', text: '=> 1024' },
    { type: 'budget', text: '   CPU: 4,510,306  Mem: 12,818' },
  ]

  const commands = [
    { cmd: 'julc new my-project', desc: 'Scaffold a Java project with validators and tests.' },
    { cmd: 'julc build', desc: 'Compile validators to UPLC and generate CIP-57 blueprints.' },
    { cmd: 'julc check', desc: 'Discover and run on-chain tests \u2014 no node required.' },
    { cmd: 'julc repl', desc: 'Interactive REPL with live CPU/memory budgets.' },
    { cmd: 'julc blueprint inspect', desc: 'Inspect blueprints \u2014 view UPLC, compute addresses.' },
  ]

  function observe(node) {
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          visible = true
          startTyping()
          observer.disconnect()
        }
      },
      { threshold: 0.2 }
    )
    observer.observe(node)
    return { destroy: () => observer.disconnect() }
  }

  async function startTyping() {
    for (const line of lines) {
      if (line.type === 'prompt') {
        // Type character by character
        let current = ''
        displayLines = [...displayLines, { type: line.type, text: '' }]
        for (const char of line.text) {
          current += char
          displayLines = [...displayLines.slice(0, -1), { type: line.type, text: current }]
          await sleep(25 + Math.random() * 15)
        }
        await sleep(300)
      } else if (line.type === 'blank') {
        displayLines = [...displayLines, line]
        await sleep(100)
      } else {
        displayLines = [...displayLines, line]
        await sleep(80)
      }
    }
  }

  function sleep(ms) {
    return new Promise((r) => setTimeout(r, ms))
  }

  // Cursor blink
  $effect(() => {
    const interval = setInterval(() => {
      cursorVisible = !cursorVisible
    }, 530)
    return () => clearInterval(interval)
  })
</script>

<div use:observe class="grid gap-6 lg:grid-cols-2">
  <!-- Terminal -->
  <div class="overflow-hidden rounded-xl border border-white/5 bg-[#0a0a0f]">
    <div class="flex items-center gap-2 border-b border-white/5 px-4 py-2.5">
      <div class="h-2.5 w-2.5 rounded-full bg-red-500/50"></div>
      <div class="h-2.5 w-2.5 rounded-full bg-yellow-500/50"></div>
      <div class="h-2.5 w-2.5 rounded-full bg-green-500/50"></div>
      <span class="ml-2 text-xs text-[#6b7280]" style="font-family: var(--font-mono)">julc repl</span>
    </div>
    <div class="p-5 min-h-[340px] overflow-x-auto" style="font-family: var(--font-mono)">
      {#each displayLines as line}
        <div class="text-[13px] leading-[1.7] {
          line.type === 'prompt' ? 'text-[#d1d5db]' :
          line.type === 'result' ? 'text-[#4ade80]' :
          line.type === 'budget' ? 'text-[#6b7280]' :
          line.type === 'system' ? 'text-[#D4600E]' :
          'text-transparent'
        }">
          {line.text || '\u00A0'}
        </div>
      {/each}
      {#if visible && displayLines.length < lines.length}
        <span class="inline-block w-2 h-4 bg-[#D4600E] {cursorVisible ? 'opacity-100' : 'opacity-0'}" style="vertical-align: text-bottom;"></span>
      {/if}
    </div>
  </div>

  <!-- Command reference -->
  <div class="space-y-3">
    {#each commands as c}
      <div class="rounded-xl border border-white/5 bg-white/[0.02] p-4 hover:border-[#D4600E]/20 transition-colors">
        <code class="text-sm font-semibold text-[#D4600E]" style="font-family: var(--font-mono)">{c.cmd}</code>
        <p class="mt-1.5 text-sm text-[#a1a1aa]">{c.desc}</p>
      </div>
    {/each}
  </div>
</div>
