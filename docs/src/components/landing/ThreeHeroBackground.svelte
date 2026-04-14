<script>
  import { onMount, onDestroy } from 'svelte'

  let container
  let fallbackCanvas
  let animationId
  let renderer, scene, camera, clock
  let graphGroup
  let raycaster, pointerVec
  let mouseX = 0, mouseY = 0
  let rawMouseX = 0, rawMouseY = 0
  let pulses = []
  let nodeRefs = [] // { mesh, glowMesh, sprite, label, isHub, baseOpacity, baseGlowOpacity, baseSpriteOpacity }
  let hoveredNode = $state(null) // { label, description, screenX, screenY }
  let reducedMotion = false
  let fallback = false

  // UPLC node labels — short terms only
  const HUB_LABELS = ['force', 'apply', 'lam', 'MkCons', 'HeadList']
  const NODE_LABELS = ['data', 'pair', 'IData', 'BData', 'TailList', 'con', 'delay', 'unit', 'error', 'MapData']

  const TERM_INFO = {
    'force':    { desc: 'Unwrap a delayed computation — evaluates a suspended term' },
    'apply':    { desc: 'Apply a function to an argument — the core of lambda calculus' },
    'lam':      { desc: 'Lambda abstraction — defines an anonymous function' },
    'HeadList': { desc: 'Get the first element of a list', java: 'list.head()' },
    'MkCons':   { desc: 'Prepend an element to a list', java: 'list.prepend(elem)' },
    'data':     { desc: 'Raw Plutus Data — the universal on-chain type', java: 'PlutusData' },
    'pair':     { desc: 'A two-element tuple (key, value)', java: 'Tuple2<A, B>' },
    'IData':    { desc: 'Wrap an integer as Plutus Data', java: 'Builtins.iData(n)' },
    'BData':    { desc: 'Wrap a bytestring as Plutus Data', java: 'Builtins.bData(bytes)' },
    'TailList': { desc: 'Get all elements after the first in a list', java: 'list.tail()' },
    'con':      { desc: 'A constant value — integer, bytestring, or unit' },
    'delay':    { desc: 'Suspend a computation for lazy evaluation' },
    'unit':     { desc: 'The empty value — Plutus equivalent of void', java: 'void' },
    'error':    { desc: 'Abort execution — validation failure', java: 'throw / return false' },
    'MapData':  { desc: 'Wrap a list of pairs as Plutus Data', java: 'Builtins.mapData(map)' },
  }

  const HUB_BLUE = 0x4a6cf7
  const HUB_BLUE_LIGHT = 0x8ba3ff
  const SILVER = 0xc8c8d8
  const TEAL = 0x14b8a6
  const PULSE_BLUE = 0x6b8cff

  function detectWebGL() {
    try {
      const c = document.createElement('canvas')
      return !!(c.getContext('webgl2') || c.getContext('webgl'))
    } catch {
      return false
    }
  }

  function createTextTexture(THREE, text, isHub) {
    const canvas = document.createElement('canvas')
    const size = isHub ? 256 : 192
    canvas.width = size
    canvas.height = 64
    const ctx = canvas.getContext('2d')
    ctx.clearRect(0, 0, size, 64)
    ctx.font = `${isHub ? 'bold ' : ''}${isHub ? 24 : 17}px 'JetBrains Mono', monospace`
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillStyle = isHub ? '#8ba3ff' : '#a1a1aa'
    ctx.fillText(text, size / 2, 32)
    const texture = new THREE.CanvasTexture(canvas)
    texture.needsUpdate = true
    return texture
  }

  function createNode(THREE, label, isHub, position) {
    const group = new THREE.Group()
    group.position.copy(position)

    // Invisible hitbox for raycasting (small sphere — not rendered visually)
    const hitGeo = new THREE.SphereGeometry(isHub ? 0.5 : 0.35, 6, 6)
    const hitMat = new THREE.MeshBasicMaterial({ visible: false })
    const mesh = new THREE.Mesh(hitGeo, hitMat)
    mesh.userData = { label }
    group.add(mesh)

    // Small dot at the node center — subtle anchor point
    const dotGeo = new THREE.CircleGeometry(isHub ? 0.06 : 0.04, 8)
    const dotColor = isHub ? HUB_BLUE : SILVER
    const baseOpacity = isHub ? 0.35 : 0.15
    const dotMat = new THREE.MeshBasicMaterial({
      color: dotColor,
      transparent: true,
      opacity: baseOpacity,
      side: THREE.DoubleSide,
    })
    const dot = new THREE.Mesh(dotGeo, dotMat)
    group.add(dot)

    // Glow for hubs — very subtle
    let glowMesh = null
    const baseGlowOpacity = 0.03
    if (isHub) {
      const glowGeo = new THREE.CircleGeometry(0.3, 12)
      const glowMat = new THREE.MeshBasicMaterial({
        color: HUB_BLUE,
        transparent: true,
        opacity: baseGlowOpacity,
        side: THREE.DoubleSide,
      })
      glowMesh = new THREE.Mesh(glowGeo, glowMat)
      group.add(glowMesh)
    }

    // Label sprite — this IS the node visually
    const texture = createTextTexture(THREE, label, isHub)
    const baseSpriteOpacity = isHub ? 0.55 : 0.3
    const spriteMat = new THREE.SpriteMaterial({
      map: texture,
      transparent: true,
      opacity: baseSpriteOpacity,
    })
    const sprite = new THREE.Sprite(spriteMat)
    sprite.scale.set(isHub ? 2.8 : 2.0, 0.65, 1)
    sprite.position.y = 0 // centered on node position (text IS the node)
    group.add(sprite)

    return { group, mesh, glowMesh, sprite, label, isHub, color: dotColor, baseOpacity, baseGlowOpacity, baseSpriteOpacity }
  }

  function createEdge(THREE, p1, p2) {
    const geo = new THREE.BufferGeometry().setFromPoints([p1, p2])
    const mat = new THREE.LineBasicMaterial({
      color: 0x3a3a5a,
      transparent: true,
      opacity: 0.2,
    })
    return new THREE.Line(geo, mat)
  }

  function createPulse(THREE, start, end) {
    const geo = new THREE.SphereGeometry(0.08, 6, 6)
    const mat = new THREE.MeshBasicMaterial({
      color: PULSE_BLUE,
      transparent: true,
      opacity: 0.8,
    })
    const mesh = new THREE.Mesh(geo, mat)
    mesh.position.copy(start)
    return {
      mesh,
      start: start.clone(),
      end: end.clone(),
      progress: 0,
      speed: 0.15 + Math.random() * 0.2,
    }
  }

  async function initScene() {
    const THREE = await import('three')

    const w = container.clientWidth
    const h = container.clientHeight

    // Renderer
    renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true })
    renderer.setSize(w, h)
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    container.appendChild(renderer.domElement)

    // Scene
    scene = new THREE.Scene()
    clock = new THREE.Clock()

    // Camera
    camera = new THREE.PerspectiveCamera(45, w / h, 0.1, 100)
    camera.position.set(0, 0, 8)
    camera.lookAt(0, 0, 0)

    // Graph group (for rotation)
    graphGroup = new THREE.Group()
    scene.add(graphGroup)

    // Raycaster for hover detection
    raycaster = new THREE.Raycaster()
    pointerVec = new THREE.Vector2()

    // Create nodes in a spread-out arrangement
    const nodes = []
    const positions = [
      // Hubs — central area
      new THREE.Vector3(-1.5, 1.2, 0.5),
      new THREE.Vector3(1.8, 0.8, -0.3),
      new THREE.Vector3(0, -0.5, 0.8),
      new THREE.Vector3(-2.2, -1.0, -0.5),
      new THREE.Vector3(2.0, -1.2, 0.3),
      // Regular — spread wider
      new THREE.Vector3(-0.5, 2.2, -0.8),
      new THREE.Vector3(0.8, 2.0, 0.6),
      new THREE.Vector3(-2.8, 0.2, 0.3),
      new THREE.Vector3(3.0, 0.0, -0.6),
      new THREE.Vector3(-1.0, -2.0, 0.4),
      new THREE.Vector3(1.2, -2.2, -0.5),
      new THREE.Vector3(0, 0.5, -1.5),
      new THREE.Vector3(-1.8, 1.8, -1.0),
      new THREE.Vector3(2.5, 1.5, 0.8),
    ]

    for (let i = 0; i < positions.length; i++) {
      const isHub = i < HUB_LABELS.length
      const label = isHub ? HUB_LABELS[i] : NODE_LABELS[i - HUB_LABELS.length]
      if (!label) continue
      const node = createNode(THREE, label, isHub, positions[i])
      nodes.push(node)
      graphGroup.add(node.group)
    }

    // Create edges — connect nearby nodes
    const edges = []
    const edgePairs = [
      [0,1],[0,2],[0,5],[1,2],[1,3],[1,8],[2,3],[2,4],[2,11],
      [3,4],[3,7],[4,10],[5,6],[5,12],[6,13],[7,9],[8,13],[9,10],
    ]
    for (const [a, b] of edgePairs) {
      if (a < nodes.length && b < nodes.length) {
        const edge = createEdge(THREE, positions[a], positions[b])
        edges.push({ line: edge, a, b })
        graphGroup.add(edge)
      }
    }

    // Create initial pulses
    for (let i = 0; i < 4; i++) {
      const pair = edgePairs[Math.floor(Math.random() * edgePairs.length)]
      const pulse = createPulse(THREE, positions[pair[0]], positions[pair[1]])
      pulses.push(pulse)
      graphGroup.add(pulse.mesh)
    }

    // Store node refs for hover interaction
    nodeRefs = nodes

    // Store references for pulse spawning
    graphGroup.userData = { THREE, positions, edgePairs }

    // Mouse tracking — always register for tooltips (even reduced-motion)
    window.addEventListener('mousemove', handleMouseMove)
    window.addEventListener('resize', handleResize)

    if (reducedMotion) {
      renderer.render(scene, camera)
    } else {
      animate()
    }
  }

  function animate() {
    animationId = requestAnimationFrame(animate)
    const delta = clock.getDelta()
    const elapsed = clock.getElapsedTime()

    // Slow auto-rotation (time-based)
    graphGroup.rotation.y += delta * 0.08

    // Mouse parallax — gentle camera tilt
    const targetX = mouseX * 0.3
    const targetY = mouseY * 0.2
    camera.position.x += (targetX - camera.position.x) * 0.02
    camera.position.y += (targetY - camera.position.y) * 0.02
    camera.lookAt(0, 0, 0)

    // Animate pulses
    const { THREE, positions, edgePairs } = graphGroup.userData
    for (let i = pulses.length - 1; i >= 0; i--) {
      const p = pulses[i]
      p.progress += p.speed * delta
      if (p.progress >= 1) {
        // Reset pulse on a random edge
        const pair = edgePairs[Math.floor(Math.random() * edgePairs.length)]
        p.start.copy(positions[pair[0]])
        p.end.copy(positions[pair[1]])
        p.progress = 0
        p.speed = 0.15 + Math.random() * 0.2
      }
      p.mesh.position.lerpVectors(p.start, p.end, p.progress)
      p.mesh.material.opacity = 0.35 * Math.sin(p.progress * Math.PI)
    }

    updateHover()
    renderer.render(scene, camera)
  }

  // Extracted hover logic — called from animate() and handleMouseMove() (reduced-motion)
  function updateHover() {
    if (!raycaster || nodeRefs.length === 0 || !container) return
    const { THREE } = graphGroup.userData

    pointerVec.set(mouseX, mouseY)
    raycaster.setFromCamera(pointerVec, camera)
    const meshes = nodeRefs.map(n => n.mesh)
    const intersects = raycaster.intersectObjects(meshes)

    let hitLabel = null
    if (intersects.length > 0) {
      hitLabel = intersects[0].object.userData.label
    }

    // Update hover state for each node
    for (const n of nodeRefs) {
      const isHovered = n.label === hitLabel
      // Dot brightens on hover
      n.mesh.material.opacity = isHovered ? Math.min(n.baseOpacity + 0.3, 0.7) : n.baseOpacity
      if (n.glowMesh) {
        n.glowMesh.material.opacity = isHovered ? 0.15 : n.baseGlowOpacity
      }
      // Text label — primary visual, brightens significantly on hover
      n.sprite.material.opacity = isHovered ? 0.95 : n.baseSpriteOpacity
    }

    // Update tooltip
    if (hitLabel && TERM_INFO[hitLabel]) {
      const hitNode = nodeRefs.find(n => n.label === hitLabel)
      if (hitNode) {
        const worldPos = new THREE.Vector3()
        hitNode.group.getWorldPosition(worldPos)
        worldPos.project(camera)
        const rect = container.getBoundingClientRect()
        const screenX = ((worldPos.x + 1) / 2) * rect.width
        const screenY = ((-worldPos.y + 1) / 2) * rect.height
        const clampedX = Math.max(20, Math.min(screenX, rect.width - 200))
        const clampedY = Math.max(20, Math.min(screenY - 50, rect.height - 60))
        const info = TERM_INFO[hitLabel]
          hoveredNode = { label: hitLabel, description: info.desc, java: info.java || null, screenX: clampedX, screenY: clampedY }
      }
    } else {
      hoveredNode = null
    }

    // Cursor style
    container.style.cursor = hitLabel ? 'pointer' : 'default'

    // In reduced-motion, re-render to show highlight changes
    if (reducedMotion && renderer) {
      renderer.render(scene, camera)
    }
  }

  function handleMouseMove(e) {
    if (!container) return
    const rect = container.getBoundingClientRect()
    mouseX = ((e.clientX - rect.left) / rect.width - 0.5) * 2
    mouseY = -((e.clientY - rect.top) / rect.height - 0.5) * 2
    rawMouseX = e.clientX - rect.left
    rawMouseY = e.clientY - rect.top

    // In reduced-motion, run hover check on mousemove since animate() doesn't loop
    if (reducedMotion) updateHover()
  }

  // 2D canvas fallback — simplified node network for mobile/no-WebGL
  function initFallback2D() {
    if (!fallbackCanvas) return
    const dpr = window.devicePixelRatio || 1
    const w = fallbackCanvas.parentElement.clientWidth
    const h = fallbackCanvas.parentElement.clientHeight
    fallbackCanvas.width = w * dpr
    fallbackCanvas.height = h * dpr
    fallbackCanvas.style.width = w + 'px'
    fallbackCanvas.style.height = h + 'px'
    const ctx = fallbackCanvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

    // Node positions (2D)
    const nodes2d = [
      { x: w * 0.15, y: h * 0.25, label: 'force', hub: true },
      { x: w * 0.45, y: h * 0.15, label: 'apply', hub: true },
      { x: w * 0.75, y: h * 0.3, label: 'lam', hub: true },
      { x: w * 0.3, y: h * 0.55, label: 'HeadList', hub: true },
      { x: w * 0.65, y: h * 0.6, label: 'MkCons', hub: true },
      { x: w * 0.1, y: h * 0.7, label: 'data', hub: false },
      { x: w * 0.5, y: h * 0.8, label: 'pair', hub: false },
      { x: w * 0.85, y: h * 0.5, label: 'IData', hub: false },
      { x: w * 0.2, y: h * 0.45, label: 'con', hub: false },
      { x: w * 0.55, y: h * 0.4, label: 'delay', hub: false },
      { x: w * 0.9, y: h * 0.75, label: 'unit', hub: false },
      { x: w * 0.35, y: h * 0.85, label: 'BData', hub: false },
    ]

    const edges2d = [
      [0,1],[0,3],[0,8],[1,2],[1,4],[1,9],[2,4],[2,7],[3,5],[3,8],
      [4,6],[4,9],[5,11],[6,11],[7,10],[8,9],
    ]

    // Draw edges
    for (const [a, b] of edges2d) {
      ctx.beginPath()
      ctx.moveTo(nodes2d[a].x, nodes2d[a].y)
      ctx.lineTo(nodes2d[b].x, nodes2d[b].y)
      ctx.strokeStyle = 'rgba(58, 58, 90, 0.18)'
      ctx.lineWidth = 0.8
      ctx.stroke()
    }

    // Draw node labels (text IS the node — no circles)
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    for (const n of nodes2d) {
      // Small dot anchor
      ctx.beginPath()
      ctx.arc(n.x, n.y, n.hub ? 2.5 : 1.5, 0, Math.PI * 2)
      ctx.fillStyle = n.hub ? 'rgba(74, 108, 247, 0.3)' : 'rgba(200, 200, 216, 0.15)'
      ctx.fill()

      // Label — primary visual
      ctx.font = n.hub ? "bold 13px 'JetBrains Mono', monospace" : "12px 'JetBrains Mono', monospace"
      ctx.fillStyle = n.hub ? 'rgba(139, 163, 255, 0.45)' : 'rgba(161, 161, 170, 0.25)'
      ctx.fillText(n.label, n.x, n.y - (n.hub ? 10 : 8))
    }
  }

  function handleResize() {
    if (fallback) {
      initFallback2D()
      return
    }
    if (!renderer || !container) return
    const w = container.clientWidth
    const h = container.clientHeight
    renderer.setSize(w, h)
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    if (reducedMotion) renderer.render(scene, camera)
  }

  onMount(() => {
    reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    const isMobile = window.innerWidth < 768

    if (!detectWebGL() || isMobile) {
      fallback = true
      // Wait for DOM update then init 2D fallback
      requestAnimationFrame(() => initFallback2D())
      return
    }

    initScene()
  })

  onDestroy(() => {
    if (animationId) cancelAnimationFrame(animationId)
    if (renderer) {
      renderer.dispose()
      renderer.domElement?.remove()
    }
    window.removeEventListener('mousemove', handleMouseMove)
    window.removeEventListener('resize', handleResize)
  })
</script>

{#if fallback}
  <!-- Fallback: 2D canvas node network for mobile/no-WebGL -->
  <div class="fallback-bg">
    <canvas bind:this={fallbackCanvas}></canvas>
  </div>
{:else}
  <div class="three-container" bind:this={container}>
    {#if hoveredNode}
      <div
        class="tooltip"
        style="left: {hoveredNode.screenX}px; top: {hoveredNode.screenY}px;"
      >
        <span class="tooltip-label">{hoveredNode.label}</span>
        <span class="tooltip-desc">{hoveredNode.description}</span>
        {#if hoveredNode.java}
          <span class="tooltip-java">Java: <code>{hoveredNode.java}</code></span>
        {/if}
      </div>
    {/if}
  </div>
{/if}

<style>
  .three-container {
    position: absolute;
    inset: 0;
    z-index: 0;
    overflow: hidden;
  }

  .three-container :global(canvas) {
    display: block;
  }

  .tooltip {
    position: absolute;
    z-index: 2;
    pointer-events: none;
    background: rgba(10, 10, 15, 0.92);
    border: 1px solid rgba(74, 108, 247, 0.4);
    border-radius: 8px;
    padding: 8px 12px;
    max-width: 260px;
    backdrop-filter: blur(8px);
    transition: opacity 0.15s ease;
  }

  .tooltip-label {
    display: block;
    font-family: 'JetBrains Mono', monospace;
    font-size: 13px;
    font-weight: 700;
    color: #8ba3ff;
    margin-bottom: 3px;
  }

  .tooltip-desc {
    display: block;
    font-family: 'Inter', sans-serif;
    font-size: 11.5px;
    color: #a1a1aa;
    line-height: 1.4;
  }

  .tooltip-java {
    display: block;
    font-family: 'Inter', sans-serif;
    font-size: 11px;
    color: #D4600E;
    margin-top: 4px;
    padding-top: 4px;
    border-top: 1px solid rgba(255, 255, 255, 0.06);
  }

  .tooltip-java code {
    font-family: 'JetBrains Mono', monospace;
    font-size: 11.5px;
    color: #f5a66a;
  }

  .fallback-bg {
    position: absolute;
    inset: 0;
    z-index: 0;
    overflow: hidden;
  }

  .fallback-bg canvas {
    display: block;
    width: 100%;
    height: 100%;
  }
</style>
