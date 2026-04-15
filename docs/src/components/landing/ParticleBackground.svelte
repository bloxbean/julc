<script>
  import { onMount, onDestroy } from 'svelte'

  let canvas
  let animationId
  let particles = []
  let mouseX = -1000
  let mouseY = -1000
  let reducedMotion = false
  const PARTICLE_COUNT = 70
  const CONNECTION_DIST = 130
  const MOUSE_RADIUS = 150
  const COLORS = [
    { r: 212, g: 96, b: 14 },    // #D4600E orange
    { r: 245, g: 166, b: 106 },   // #f5a66a light orange
    { r: 20, g: 184, b: 166 },    // #14b8a6 teal
  ]

  function createParticle(w, h) {
    const isTeal = Math.random() < 0.2
    const color = isTeal ? COLORS[2] : (Math.random() < 0.5 ? COLORS[0] : COLORS[1])
    const isAccent = Math.random() < 0.07
    const isGlow = Math.random() < 0.08
    return {
      x: Math.random() * w,
      y: Math.random() * h,
      vx: (Math.random() - 0.5) * 0.4,
      vy: (Math.random() - 0.5) * 0.4,
      radius: isAccent ? 3 + Math.random() * 1 : (isGlow ? 2.5 + Math.random() * 1 : 1 + Math.random() * 2.5),
      color,
      alpha: isAccent ? 0.35 : (isGlow ? 0.25 : 0.1 + Math.random() * 0.15),
      isGlow: isGlow || isAccent,
    }
  }

  function init() {
    const parent = canvas.parentElement
    const w = parent.clientWidth
    const h = parent.clientHeight
    canvas.width = w
    canvas.height = h
    particles = []
    for (let i = 0; i < PARTICLE_COUNT; i++) {
      particles.push(createParticle(w, h))
    }
  }

  function lerp(a, b, t) {
    return a + (b - a) * t
  }

  function animate() {
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    const w = canvas.width
    const h = canvas.height

    ctx.clearRect(0, 0, w, h)

    // Reduced motion: draw static particles once, no animation loop
    if (reducedMotion) {
      for (const p of particles) {
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${p.color.r}, ${p.color.g}, ${p.color.b}, ${p.alpha * 0.6})`
        ctx.fill()
      }
      // Static connections
      for (let i = 0; i < particles.length; i++) {
        for (let j = i + 1; j < particles.length; j++) {
          const dx = particles[i].x - particles[j].x
          const dy = particles[i].y - particles[j].y
          const dist = Math.sqrt(dx * dx + dy * dy)
          if (dist < CONNECTION_DIST) {
            const opacity = (1 - dist / CONNECTION_DIST) * 0.08
            ctx.beginPath()
            ctx.moveTo(particles[i].x, particles[i].y)
            ctx.lineTo(particles[j].x, particles[j].y)
            ctx.strokeStyle = `rgba(212, 96, 14, ${opacity})`
            ctx.lineWidth = 0.5
            ctx.stroke()
          }
        }
      }
      return // No animation loop — single static frame
    }

    // Mouse follow: soft radial glow at cursor
    if (mouseX > 0 && mouseY > 0) {
      const grad = ctx.createRadialGradient(mouseX, mouseY, 0, mouseX, mouseY, 180)
      grad.addColorStop(0, 'rgba(212, 96, 14, 0.06)')
      grad.addColorStop(0.5, 'rgba(212, 96, 14, 0.02)')
      grad.addColorStop(1, 'rgba(212, 96, 14, 0)')
      ctx.beginPath()
      ctx.arc(mouseX, mouseY, 180, 0, Math.PI * 2)
      ctx.fillStyle = grad
      ctx.fill()
    }

    // Update positions
    for (const p of particles) {
      p.x += p.vx
      p.y += p.vy
      if (p.x < 0 || p.x > w) p.vx *= -1
      if (p.y < 0 || p.y > h) p.vy *= -1
    }

    // Draw connections with gradient color mix
    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const a = particles[i]
        const b = particles[j]
        const dx = a.x - b.x
        const dy = a.y - b.y
        const dist = Math.sqrt(dx * dx + dy * dy)
        if (dist < CONNECTION_DIST) {
          let baseOpacity = (1 - dist / CONNECTION_DIST) * 0.15

          // Boost near mouse
          if (mouseX > 0) {
            const midX = (a.x + b.x) / 2
            const midY = (a.y + b.y) / 2
            const mDist = Math.sqrt((midX - mouseX) ** 2 + (midY - mouseY) ** 2)
            if (mDist < MOUSE_RADIUS) {
              baseOpacity += (1 - mDist / MOUSE_RADIUS) * 0.08
            }
          }

          // Gradient line: blend colors of both particles
          const r = Math.round(lerp(a.color.r, b.color.r, 0.5))
          const g = Math.round(lerp(a.color.g, b.color.g, 0.5))
          const bl = Math.round(lerp(a.color.b, b.color.b, 0.5))

          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.strokeStyle = `rgba(${r}, ${g}, ${bl}, ${baseOpacity})`
          ctx.lineWidth = 0.6
          ctx.stroke()
        }
      }
    }

    // Draw particles
    for (const p of particles) {
      let alphaBoost = 0

      // Mouse proximity boost
      if (mouseX > 0) {
        const mDist = Math.sqrt((p.x - mouseX) ** 2 + (p.y - mouseY) ** 2)
        if (mDist < MOUSE_RADIUS) {
          alphaBoost = (1 - mDist / MOUSE_RADIUS) * 0.12
        }
      }

      const finalAlpha = Math.min(p.alpha + alphaBoost, 0.5)

      ctx.beginPath()
      ctx.arc(p.x, p.y, p.radius, 0, Math.PI * 2)
      ctx.fillStyle = `rgba(${p.color.r}, ${p.color.g}, ${p.color.b}, ${finalAlpha})`
      ctx.fill()

      if (p.isGlow) {
        ctx.beginPath()
        ctx.arc(p.x, p.y, p.radius * 3, 0, Math.PI * 2)
        ctx.fillStyle = `rgba(${p.color.r}, ${p.color.g}, ${p.color.b}, ${0.03 + alphaBoost * 0.3})`
        ctx.fill()
      }
    }

    animationId = requestAnimationFrame(animate)
  }

  function handleMouseMove(e) {
    const rect = canvas.getBoundingClientRect()
    mouseX = e.clientX - rect.left
    mouseY = e.clientY - rect.top
  }

  function handleMouseLeave() {
    mouseX = -1000
    mouseY = -1000
  }

  onMount(() => {
    reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    init()
    animate()

    // Mouse tracking only when motion is allowed (desktop only)
    if (!reducedMotion) {
      window.addEventListener('mousemove', handleMouseMove)
      window.addEventListener('mouseleave', handleMouseLeave)
    }

    let resizeTimer
    const handleResize = () => {
      clearTimeout(resizeTimer)
      resizeTimer = setTimeout(() => {
        init()
        if (reducedMotion) animate() // Re-render static frame after resize
      }, 200)
    }
    window.addEventListener('resize', handleResize)

    return () => {
      window.removeEventListener('resize', handleResize)
      window.removeEventListener('mousemove', handleMouseMove)
      window.removeEventListener('mouseleave', handleMouseLeave)
    }
  })

  onDestroy(() => {
    if (animationId) cancelAnimationFrame(animationId)
  })
</script>

<div class="particle-bg">
  <!-- Aurora gradient blobs (behind canvas) -->
  <div class="aurora aurora-1"></div>
  <div class="aurora aurora-2"></div>
  <div class="aurora aurora-3"></div>
  <canvas bind:this={canvas}></canvas>
</div>

<style>
  .particle-bg {
    position: absolute;
    inset: 0;
    z-index: 0;
    overflow: hidden;
  }

  canvas {
    display: block;
    width: 100%;
    height: 100%;
    position: relative;
    z-index: 1;
  }

  /* Aurora gradient blobs */
  .aurora {
    position: absolute;
    border-radius: 50%;
    pointer-events: none;
    z-index: 0;
  }

  .aurora-1 {
    width: 500px;
    height: 500px;
    top: -10%;
    left: -5%;
    background: radial-gradient(circle, rgba(212, 96, 14, 0.1) 0%, transparent 70%);
    filter: blur(80px);
    animation: drift1 20s ease-in-out infinite alternate;
  }

  .aurora-2 {
    width: 420px;
    height: 420px;
    top: 10%;
    right: -8%;
    background: radial-gradient(circle, rgba(20, 184, 166, 0.08) 0%, transparent 70%);
    filter: blur(90px);
    animation: drift2 25s ease-in-out infinite alternate;
  }

  .aurora-3 {
    width: 380px;
    height: 380px;
    bottom: 10%;
    left: 30%;
    background: radial-gradient(circle, rgba(245, 166, 106, 0.07) 0%, transparent 70%);
    filter: blur(100px);
    animation: drift3 30s ease-in-out infinite alternate;
  }

  @keyframes drift1 {
    0% { transform: translate(0, 0) scale(1); }
    50% { transform: translate(60px, 40px) scale(1.1); }
    100% { transform: translate(-30px, 70px) scale(0.95); }
  }

  @keyframes drift2 {
    0% { transform: translate(0, 0) scale(1); }
    50% { transform: translate(-50px, 50px) scale(1.05); }
    100% { transform: translate(40px, -30px) scale(1.1); }
  }

  @keyframes drift3 {
    0% { transform: translate(0, 0) scale(1); }
    50% { transform: translate(40px, -40px) scale(1.08); }
    100% { transform: translate(-50px, 20px) scale(0.95); }
  }

  @media (prefers-reduced-motion: reduce) {
    .aurora {
      animation-duration: 120s;
    }
    canvas {
      opacity: 0.5;
    }
  }
</style>
