<script>
  import { onMount, onDestroy } from 'svelte'

  let canvas
  let animationId
  let streams = []
  let reducedMotion = false

  // UPLC vocabulary — weighted: 70% builtins, 20% types, 10% hex
  const BUILTINS = [
    'AddInteger', 'SubtractInteger', 'MultiplyInteger', 'DivideInteger',
    'EqualsInteger', 'LessThanInteger', 'LessThanEqualsInteger',
    'AppendByteString', 'EqualsByteString', 'LengthOfByteString',
    'Sha2_256', 'Sha3_256', 'Blake2b_256',
    'IfThenElse', 'HeadList', 'TailList', 'NullList', 'MkCons',
    'FstPair', 'SndPair', 'ConstrData', 'MapData', 'ListData',
    'IData', 'BData', 'UnConstrData', 'UnIData', 'UnBData',
    'UnMapData', 'UnListData', 'MkPairData', 'MkNilData',
    'VerifyEd25519Signature', 'VerifyEcdsaSecp256k1Signature',
    'force', 'delay', 'apply', 'error',
  ]

  const TYPES = [
    'lam', 'con', 'data', 'pair', 'list', 'unit', 'bool',
    'integer', 'bytestring', 'builtin',
  ]

  const HEX = [
    '4d010000', '33222005', '1a003375', '60ae1b00', '3f5e0041',
    'a0f8c2d1', '00bb51ef', 'deadbeef', 'cafe0123', '7e3a9b0c',
  ]

  function pickToken() {
    const roll = Math.random()
    if (roll < 0.7) return BUILTINS[Math.floor(Math.random() * BUILTINS.length)]
    if (roll < 0.9) return TYPES[Math.floor(Math.random() * TYPES.length)]
    return HEX[Math.floor(Math.random() * HEX.length)]
  }

  function getColumnCount() {
    const w = window.innerWidth
    if (w < 640) return 5 + Math.floor(Math.random() * 3)
    if (w < 1024) return 8 + Math.floor(Math.random() * 4)
    return 14 + Math.floor(Math.random() * 5)
  }

  function createStream(x, h) {
    const isTeal = Math.random() < 0.1
    return {
      x,
      y: -Math.random() * h * 0.5, // stagger start positions
      token: pickToken(),
      charIndex: 0,
      trail: [],
      trailMax: 12 + Math.floor(Math.random() * 10),
      color: isTeal
        ? { r: 20, g: 184, b: 166 }
        : (Math.random() < 0.6
          ? { r: 212, g: 96, b: 14 }
          : { r: 245, g: 166, b: 106 }),
      tickCounter: 0,
      tickRate: 4 + Math.floor(Math.random() * 5), // frames between char advances (slower)
    }
  }

  function init() {
    const dpr = window.devicePixelRatio || 1
    const w = window.innerWidth
    const h = window.innerHeight
    canvas.width = w * dpr
    canvas.height = h * dpr
    canvas.style.width = w + 'px'
    canvas.style.height = h + 'px'

    const colCount = getColumnCount()
    const spacing = w / colCount
    streams = []
    for (let i = 0; i < colCount; i++) {
      const x = spacing * i + spacing * 0.3 + Math.random() * spacing * 0.4
      streams.push(createStream(x, h))
    }
  }

  function animate() {
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const dpr = window.devicePixelRatio || 1
    const w = canvas.width / dpr
    const h = canvas.height / dpr

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.clearRect(0, 0, w, h)

    const fontSize = w > 1200 ? 13 : 12
    ctx.font = `${fontSize}px 'JetBrains Mono', monospace`
    ctx.textBaseline = 'top'

    if (reducedMotion) {
      // Static: scatter tokens across canvas
      for (const s of streams) {
        for (let i = 0; i < 4; i++) {
          const token = pickToken().substring(0, 6)
          const y = h * 0.1 + Math.random() * h * 0.7
          ctx.fillStyle = `rgba(${s.color.r}, ${s.color.g}, ${s.color.b}, 0.08)`
          ctx.fillText(token, s.x, y)
        }
      }
      return
    }

    for (const s of streams) {
      s.tickCounter++

      // Advance character on tick
      if (s.tickCounter >= s.tickRate) {
        s.tickCounter = 0

        // Short tokens (<=5 chars) drop as whole words ~40% of the time
        const isShort = s.token.length <= 5
        if (isShort && s.charIndex === 0 && Math.random() < 0.4) {
          // Drop whole token at once
          s.trail.unshift({ char: s.token, y: s.y, whole: true })
          s.charIndex = s.token.length // move past it
        } else {
          const ch = s.token[s.charIndex]
          if (ch) s.trail.unshift({ char: ch, y: s.y, whole: false })
          s.charIndex++
        }

        // Trim trail
        if (s.trail.length > s.trailMax) s.trail.pop()

        if (s.charIndex >= s.token.length) {
          // Pick next token, add gap between tokens
          s.token = pickToken()
          s.charIndex = 0
          s.y += fontSize * 0.8
        }
        s.y += fontSize * 1.2
      }

      // Draw trail — slower fade so terms stay readable longer
      for (let i = 0; i < s.trail.length; i++) {
        const t = s.trail[i]
        const fade = 1 - i / s.trail.length
        const alpha = i === 0 ? 0.65 : fade * fade * 0.45 // quadratic fade, stays brighter longer
        ctx.fillStyle = `rgba(${s.color.r}, ${s.color.g}, ${s.color.b}, ${alpha})`
        ctx.fillText(t.char, s.x, t.y)
      }

      // Reset stream when fully off screen
      if (s.trail.length > 0 && s.trail[s.trail.length - 1].y > h + 50) {
        Object.assign(s, createStream(s.x, h))
        s.y = -Math.random() * 100
      }
    }

    animationId = requestAnimationFrame(animate)
  }

  onMount(() => {
    reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    init()
    animate()

    let resizeTimer
    const handleResize = () => {
      clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        init()
        if (reducedMotion) animate()
      }, 200)
    }
    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
    }
  })

  onDestroy(() => {
    if (animationId) cancelAnimationFrame(animationId)
  })
</script>

<div class="rain-bg">
  <canvas bind:this={canvas}></canvas>
</div>

<style>
  .rain-bg {
    position: fixed;
    inset: 0;
    z-index: 0;
    background-color: #0a0a0f;
    -webkit-mask-image: linear-gradient(
      to bottom,
      black 0%,
      black 40vh,
      rgba(0, 0, 0, 0.15) 80vh,
      rgba(0, 0, 0, 0.05) 100vh
    );
    mask-image: linear-gradient(
      to bottom,
      black 0%,
      black 40vh,
      rgba(0, 0, 0, 0.15) 80vh,
      rgba(0, 0, 0, 0.05) 100vh
    );
  }
  canvas {
    display: block;
  }
</style>
