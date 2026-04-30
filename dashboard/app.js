'use strict';

const API_GRAPH_URL   = '/api/graph';
const API_STATUS_URL  = '/api/status';
const API_START_URL   = '/api/start';
const API_RESET_URL   = '/api/reset';
const API_PAGE_URL    = '/api/page';
const POLL_INTERVAL   = 3000;
const MAX_BACKOFF     = 30000;
const STAGGER_MS      = 40;
const LAYOUT_DEBOUNCE_MS = 1200;

// Depth palette — 8 levels, each a distinct vivid hue
// Designed for dark canvas: high chroma, WCAG AA contrast against #07070f
// Source: Tableau 10 + Material Design vibrant subset, adapted for graph viz
const DEPTH_PALETTE = [
  '#FFD700',  // D0 — Seed: rich gold, unmissable root anchor
  '#00B4FF',  // D1 — Electric sky-blue: first children pop cold against warm root
  '#00E5C5',  // D2 — Cyan-mint: aqua family, clearly different from D1
  '#39FF14',  // D3 — Electric lime-green: maximum contrast shift into green spectrum
  '#FF8C00',  // D4 — Deep amber-orange: warm shift away from greens
  '#FF2D78',  // D5 — Hot magenta-pink: aggressive hue rotation
  '#BF5FFF',  // D6 — Electric violet: purple family, cool contrast to D5
  '#FF4500',  // D7+ — Cinnabar red-orange: terminating nodes feel urgent
];

const TOKENS = {
  bgBase:       '#07070f',
  nodeHighlight:'#ffffff',
  nodeMatch:    '#D90036',
  edgeColor:    'rgba(190, 195, 255, 0.12)',
  edgeHighlight:'rgba(255, 255, 255, 0.45)',
  labelColor:   'rgba(220, 225, 255, 0.9)',
  accentColor:  '#D90036',
};

const DOM = {};
let _layoutDebounceTimer = null; // FIX 8
let _tooltipRafId = null;        // FIX 11

function initDOMRefs() {
  DOM.setupOverlay  = document.getElementById('view-setup');
  DOM.setupForm     = document.getElementById('setup-form');
  DOM.inputSeedUrl  = document.getElementById('seed-url');
  DOM.inputMaxDepth = document.getElementById('max-depth');
  DOM.inputMaxPages = document.getElementById('max-pages');
  DOM.inputMaxBreadth = document.getElementById('max-breadth');
  DOM.inputKeyword  = document.getElementById('keyword');
  DOM.formError     = document.getElementById('form-error');
  DOM.btnStart      = document.getElementById('btn-start-crawl');

  DOM.app           = document.getElementById('app');
  DOM.statusDot     = document.getElementById('status-dot');
  DOM.statusText    = document.getElementById('status-text');
  DOM.btnReset      = document.getElementById('btn-reset');

  DOM.statPages     = document.getElementById('stat-pages');
  DOM.statPagesMax  = document.getElementById('stat-pages-max');
  DOM.statEdges     = document.getElementById('stat-edges');
  DOM.statDepth     = document.getElementById('stat-depth');
  DOM.statDepthMax  = document.getElementById('stat-depth-max');
  DOM.statMatches   = document.getElementById('stat-matches');
  DOM.statTimer     = document.getElementById('stat-timer');       
  DOM.statThroughput = document.getElementById('stat-throughput');
  DOM.statBreadthSeen = document.getElementById('stat-breadth-seen');
  DOM.statBreadthMax  = document.getElementById('stat-breadth-max');
  DOM.statMatchesCard = document.getElementById('stat-matches-card');

  DOM.domainList    = document.getElementById('domain-list');

  DOM.errorBanner   = document.getElementById('error-banner');
  DOM.errorMessage  = document.getElementById('error-message');
  DOM.btnCloseError = document.getElementById('btn-close-error');

  DOM.graphLoading  = document.getElementById('graph-loading');

  DOM.drawer        = document.getElementById('detail-drawer');
  DOM.drawerClose   = document.getElementById('drawer-close');
  DOM.drawerDepth   = document.getElementById('drawer-depth');
  DOM.drawerTitle   = document.getElementById('drawer-title');
  DOM.drawerUrl     = document.getElementById('drawer-url');
  DOM.metaFetch     = document.getElementById('meta-fetch-time');
  DOM.metaKeyword   = document.getElementById('meta-keyword');
  DOM.metaTimestamp = document.getElementById('meta-timestamp');
  DOM.metaParent    = document.getElementById('meta-parent');
  DOM.btnViewData   = document.getElementById('btn-view-data'); 

  DOM.tooltip       = document.getElementById('cy-tooltip');

  DOM.dataModal     = document.getElementById('data-modal');
  DOM.modalUrlDisplay = document.getElementById('modal-url-display');
  DOM.modalText     = document.getElementById('modal-text-content');
  DOM.btnModalClose = document.getElementById('btn-modal-close');
}

function getInitialState() {
  return {
    knownNodeIds:       new Set(),
    knownEdgeIds:       new Set(),
    domainMap:          new Map(),
    failCount:          0,
    pollTimer:          null,
    timerInterval:      null,
    crawlStartTime:     null,
    maxDepth:           null,
    maxPages:           null,
    maxBreadth:         null,
    maxDepthSeen:       0,
    maxBreadthSeen:     0,
    outDegree:          new Map(),
    matchCount:         0,
    keyword:            '',
    isPaused:           false,  // explicit flag — avoids fragile textContent checks
    cy:                 null,
    activeDomain:       null,
    crawlFinished:      false,
    currentNodeUrl:     null,
    graphSinceIndex:    0,
    viewDataAbort:      null,
    layoutRan:          false,
  };
}

let state = getInitialState();

function initSetupForm() {
  DOM.setupForm.addEventListener('submit', async function(e) {
    e.preventDefault();
    clearFormError();

    const seedUrl    = DOM.inputSeedUrl.value.trim();
    const maxDepth   = parseInt(DOM.inputMaxDepth.value, 10);
    const maxPages   = parseInt(DOM.inputMaxPages.value, 10);
    const maxBreadth = parseInt(DOM.inputMaxBreadth.value, 10);
    const keyword    = DOM.inputKeyword.value.trim();

    if (!seedUrl) return showFormError('Seed URL is required.');
    if (!seedUrl.startsWith('http://') && !seedUrl.startsWith('https://')) return showFormError('Seed URL must start with http:// or https://');
    if (isNaN(maxBreadth) || maxBreadth < 1 || maxBreadth > 500) return showFormError('Max Breadth must be between 1 and 500.');

    setButtonLoading(true);

    try {
      const response = await fetch(API_START_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ seedUrl, maxDepth, keyword, maxPages, maxBreadth,
          enableScraper: document.getElementById('enable-scraper')?.checked ?? true }),
      });

      let data = {};
      try { data = await response.json(); } catch (_) {}

      if (response.status === 202) {
        state.maxDepth   = maxDepth;
        state.maxPages   = maxPages;
        state.maxBreadth = maxBreadth;
        state.keyword    = keyword;
        if (DOM.statPagesMax)    DOM.statPagesMax.textContent    = maxPages;
        if (DOM.statDepthMax)    DOM.statDepthMax.textContent    = maxDepth;
        if (DOM.statBreadthMax)  DOM.statBreadthMax.textContent  = maxBreadth;
        // show Matches card only when a keyword was supplied
        if (DOM.statMatchesCard) {
          DOM.statMatchesCard.style.display = keyword ? '' : 'none';
        }
        transitionToGraphView();
      } else {
        showFormError(data.error || 'Server error (HTTP ' + response.status + ')');
        setButtonLoading(false);
      }
    } catch (err) {
      showFormError('Could not connect to the crawler engine. Is the Java server running?');
      setButtonLoading(false);
    }
  });

  DOM.inputSeedUrl.addEventListener('input', clearFormError);
  
  DOM.btnReset.addEventListener('click', async () => {
    try { await fetch(API_RESET_URL, { method: 'POST' }); } catch (_) {}
    resetToSetupScreen();
  });

  DOM.btnCloseError.addEventListener('click', hideErrorBanner);

  // ── Pause / Resume ────────────────────────────────────────────────────────
  // Use an explicit boolean flag instead of reading textContent (fragile).
  DOM.btnPause = document.getElementById('btn-pause');
  DOM.btnStop  = document.getElementById('btn-stop');

  if (DOM.btnPause) {
    DOM.btnPause.addEventListener('click', async () => {
      const currentlyPaused = state.isPaused;
      const endpoint = currentlyPaused ? '/api/resume' : '/api/pause';
      DOM.btnPause.disabled = true;
      try {
        const resp = await fetch(endpoint, { method: 'POST' });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        state.isPaused = !currentlyPaused;
        DOM.btnPause.textContent = state.isPaused ? 'Resume' : 'Pause';
        setStatus(state.isPaused ? 'connecting' : 'crawling',
                  state.isPaused ? 'Paused'     : 'Running');
      } catch (e) {
        showErrorBanner('Failed to ' + (currentlyPaused ? 'resume' : 'pause') + ': ' + e.message);
      } finally {
        DOM.btnPause.disabled = false;
      }
    });
  }

  // ── Stop ──────────────────────────────────────────────────────────────────
  // Sends abort to engine, polls /api/status until IDLE, then cleans up UI.
  if (DOM.btnStop) {
    DOM.btnStop.addEventListener('click', async () => {
      DOM.btnStop.disabled  = true;
      DOM.btnPause.disabled = true;
      setStatus('connecting', 'Stopping…');
      try {
        await fetch(API_RESET_URL, { method: 'POST' });
      } catch (_) { /* engine may already be dead — that's fine */ }

      let attempts = 0;
      const pollStop = setInterval(async () => {
        attempts++;
        try {
          const resp = await fetch(API_STATUS_URL, { cache: 'no-store' });
          if (resp.ok) {
            const data = await resp.json();
            if (data.status === 'idle' || data.status === 'finished' || attempts >= 10) {
              clearInterval(pollStop);
              resetToSetupScreen();
            }
          }
        } catch (_) {
          if (attempts >= 10) { clearInterval(pollStop); resetToSetupScreen(); }
        }
      }, 500);
    });
  }
}

// ── Shared cleanup: called by Stop button, Reset Engine button ─────────────
function resetToSetupScreen() {
  if (state.cy)            state.cy.destroy();
  if (state.pollTimer)     clearTimeout(state.pollTimer);
  if (state.timerInterval) clearInterval(state.timerInterval);
  closeModal();
  state = getInitialState();

  DOM.app.style.display = 'none';
  DOM.setupOverlay.classList.remove('hidden');
  setButtonLoading(false);

  // Reset header buttons to default state for next crawl
  if (DOM.btnPause) { DOM.btnPause.textContent = 'Pause'; DOM.btnPause.disabled = false; }
  if (DOM.btnStop)  { DOM.btnStop.disabled = false; }

  if (DOM.statPages)       DOM.statPages.textContent       = '';
  if (DOM.statPagesMax)    DOM.statPagesMax.textContent    = '';
  if (DOM.statBreadthSeen) DOM.statBreadthSeen.textContent = '';
  if (DOM.statBreadthMax)  DOM.statBreadthMax.textContent  = '';
  if (DOM.statDepth)       DOM.statDepth.textContent       = '';
  if (DOM.statDepthMax)    DOM.statDepthMax.textContent    = '';
  if (DOM.statMatches)     DOM.statMatches.textContent     = '';
  if (DOM.statMatchesCard) DOM.statMatchesCard.style.display = 'none';
  if (DOM.statTimer)       DOM.statTimer.textContent       = '';
  if (DOM.statThroughput)  DOM.statThroughput.textContent  = '';
  DOM.domainList.innerHTML = '<li class="domain-item--empty label-mono" id="domain-empty">Awaiting crawl data&hellip;</li>';
  DOM.graphLoading.classList.remove('hidden');
  hideErrorBanner();
  closeDrawer();
}

function showFormError(msg) { DOM.formError.textContent = msg; DOM.formError.removeAttribute('hidden'); }
function clearFormError() { DOM.formError.setAttribute('hidden', ''); DOM.formError.textContent = ''; }
function setButtonLoading(loading) {
  DOM.btnStart.disabled = loading;
  DOM.btnStart.textContent = loading ? 'Starting...' : 'Start Crawl';
}

function transitionToGraphView() {
  DOM.setupOverlay.classList.add('fade-out');
  DOM.app.style.display = 'flex';
  state.crawlStartTime = Date.now(); 
  startTimer();
  setTimeout(() => {
    DOM.setupOverlay.classList.add('hidden');
    initCytoscape();
    startPolling();
  }, 400);
}

function startTimer() {
  if (state.timerInterval) clearInterval(state.timerInterval);
  state.timerInterval = setInterval(() => {
    if (!state.crawlStartTime || !DOM.statTimer) return;
    const elapsedSec = Math.floor((Date.now() - state.crawlStartTime) / 1000);
    const mm = String(Math.floor(elapsedSec / 60)).padStart(2, '0');
    const ss = String(elapsedSec % 60).padStart(2, '0');
    DOM.statTimer.textContent = mm + ':' + ss;
    const pages = state.knownNodeIds.size;
    if (elapsedSec > 0 && DOM.statThroughput) {
      DOM.statThroughput.textContent = (pages / elapsedSec).toFixed(1) + ' p/s';
    }
  }, 1000);
}

function stopTimer() {
  if (state.timerInterval) { clearInterval(state.timerInterval); state.timerInterval = null; }
}

function initCytoscape() {
  state.cy = cytoscape({
    container: document.getElementById('cy'),
    elements: [],
    style: buildCytoscapeStyle(),
    layout: { name: 'preset' },
    minZoom: 0.05, maxZoom: 8,
    userZoomingEnabled: true, userPanningEnabled: true, boxSelectionEnabled: false,
    styleEnabled: true,
  });

  document.getElementById('cy').style.background = TOKENS.bgBase;

  state.cy.on('tap', 'node', function(evt) {
    openDrawer(evt.target);
    highlightNeighborhood(evt.target);
  });
  state.cy.on('tap', function(evt) {
    if (evt.target === state.cy) { closeDrawer(); resetHighlight(); }
  });

  initTooltip();
}

// FIX 11: Remove synchronous layout thrashing with requestAnimationFrame
function initTooltip() {
  if (!DOM.tooltip) return;
  state.cy.on('mouseover', 'node', function(evt) {
    const label = evt.target.data('label') || evt.target.id();
    DOM.tooltip.textContent = label;
    DOM.tooltip.classList.remove('hidden');
  });
  state.cy.on('mouseout', 'node', function() {
    DOM.tooltip.classList.add('hidden');
  });
  state.cy.on('mousemove', function(evt) {
    if (!evt.originalEvent) return;
    const x = evt.originalEvent.clientX;
    const y = evt.originalEvent.clientY;
    if (_tooltipRafId) cancelAnimationFrame(_tooltipRafId);
    _tooltipRafId = requestAnimationFrame(() => {
        const tw = DOM.tooltip.offsetWidth  || 120;
        const th = DOM.tooltip.offsetHeight || 24;
        const lx = Math.min(x + 14, window.innerWidth  - tw - 10);
        const ly = Math.max(y - 10, th + 4);
        DOM.tooltip.style.left = lx + 'px';
        DOM.tooltip.style.top  = ly + 'px';
        _tooltipRafId = null;
    });
  });
  document.getElementById('cy').style.cursor = 'crosshair';
  state.cy.on('mouseover', 'node', function() { document.getElementById('cy').style.cursor = 'pointer'; });
  state.cy.on('mouseout',  'node', function() { document.getElementById('cy').style.cursor = 'crosshair'; });
}

function buildCytoscapeStyle() {
  // helper: per-depth glow color (same as fill, used for shadow)
  const depthColor = d => DEPTH_PALETTE[Math.min(d, DEPTH_PALETTE.length - 1)];
  const depthSize  = d => d === 0 ? 22 : d === 1 ? 14 : d === 2 ? 10 : d === 3 ? 8 : 6;

  return [
    {
      selector: 'node',
      style: {
        'background-color': node => depthColor(node.data('depth') || 0),
        'background-opacity': 1,
        'border-width': node => (node.data('depth') || 0) === 0 ? 3 : 1.5,
        'border-color': node => depthColor(node.data('depth') || 0),
        'border-opacity': 0.6,
        'width':  node => depthSize(node.data('depth') || 0),
        'height': node => depthSize(node.data('depth') || 0),
        // Cytoscape shadow = glow effect
        'shadow-blur':    node => (node.data('depth') || 0) === 0 ? 24 : 14,
        'shadow-color':   node => depthColor(node.data('depth') || 0),
        'shadow-opacity': node => (node.data('depth') || 0) === 0 ? 0.9 : 0.65,
        'shadow-offset-x': 0,
        'shadow-offset-y': 0,
        'label': '',
        'overlay-opacity': 0,
        'transition-property': 'background-color, width, height, border-color, shadow-opacity',
        'transition-duration': '0.25s',
      }
    },
    // Seed node (D0): gold, large, labelled
    {
      selector: 'node[depth = 0]',
      style: {
        'width': 22, 'height': 22,
        'border-width': 3,
        'border-color': '#FFD700',
        'border-opacity': 1,
        'shadow-blur': 28,
        'shadow-color': '#FFD700',
        'shadow-opacity': 1,
        'label': 'data(label)',
        'font-family': 'IBM Plex Mono, monospace',
        'font-size': 10,
        'font-weight': 700,
        'color': '#FFFFFF',
        'text-valign': 'bottom', 'text-halign': 'center',
        'text-margin-y': 6,
        'text-background-color': 'rgba(5,5,14,0.85)',
        'text-background-opacity': 1,
        'text-background-padding': '4px',
        'text-border-width': 0,
      }
    },
    // Keyword match: white pulsing ring
    {
      selector: 'node[?keywordMatch]',
      style: {
        'background-color':   '#FF2D78',
        'background-opacity': 1,
        'border-width': 2.5,
        'border-color': '#ffffff',
        'border-opacity': 1,
        'shadow-blur': 18,
        'shadow-color': '#FF2D78',
        'shadow-opacity': 0.9,
        'width': 12, 'height': 12,
      }
    },
    // Highlighted / selected: show full label with bright readable text
    {
      selector: 'node:selected, node.highlighted',
      style: {
        'background-opacity': 1,
        'border-width': 2,
        'border-color': '#ffffff',
        'border-opacity': 1,
        'shadow-blur': 22,
        'shadow-opacity': 0.9,
        'width': node => depthSize(node.data('depth') || 0) + 4,
        'height': node => depthSize(node.data('depth') || 0) + 4,
        'label': 'data(label)',
        'font-family': 'IBM Plex Mono, monospace',
        'font-size': 9,
        'font-weight': 600,
        'color': '#FFFFFF',                           // ← bright white, always readable
        'text-valign': 'bottom', 'text-halign': 'center',
        'text-margin-y': 5,
        'text-background-color': 'rgba(5,5,14,0.9)', // ← near-black glass pill
        'text-background-opacity': 1,
        'text-background-padding': '3px',
      }
    },
    { selector: 'node.dimmed', style: { 'opacity': 0.06, 'shadow-opacity': 0 } },
    {
      selector: 'edge',
      style: {
        'width': node => 0.7,
        'line-color': 'rgba(160, 170, 220, 0.18)',
        'target-arrow-color': 'rgba(160, 170, 220, 0.35)',
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.65,
        'curve-style': 'bezier',
        'opacity': 0.7,
      }
    },
    {
      selector: 'edge.highlighted',
      style: {
        'line-color':          'rgba(255,255,255,0.55)',
        'target-arrow-color':  'rgba(255,255,255,0.75)',
        'target-arrow-shape':  'triangle',
        'arrow-scale': 0.9,
        'width': 1.4,
        'opacity': 1,
      }
    },
    { selector: 'edge.dimmed', style: { 'opacity': 0.03 } },
  ];
}

async function fetchAndUpdate() {
  if (state.crawlFinished) return;

  try {
    // FIX 3: Pass delta tracking token to prevent memory crashes
    const [graphResp, statusResp] = await Promise.all([
      fetch(API_GRAPH_URL + '?since=' + state.graphSinceIndex, { cache: 'no-store' }),
      fetch(API_STATUS_URL, { cache: 'no-store' }),
    ]);

    if (!graphResp.ok) throw new Error('Graph HTTP ' + graphResp.status);

    const graphData  = await graphResp.json();
    const statusData = statusResp.ok ? await statusResp.json() : null;

    state.failCount = 0;
    hideErrorBanner();
    applyGraphData(graphData);

    if (statusData) syncStatus(statusData);

  } catch (err) {
    state.failCount++;
    if (err.message && err.message.includes('HTTP') || err.name === 'TypeError') {
      setStatus('error', 'Engine offline');
      showErrorBanner('Disconnected from engine. Retrying... (' + err.message + ')');
    }
  }

  if (!state.crawlFinished) {
    const delay = state.failCount === 0 ? POLL_INTERVAL : Math.min(POLL_INTERVAL * Math.pow(2, state.failCount - 1), MAX_BACKOFF);
    state.pollTimer = setTimeout(fetchAndUpdate, delay);
  }
}

function startPolling() {
  setStatus('crawling', 'Running');
  state.pollTimer = setTimeout(fetchAndUpdate, 1500);
}

function syncStatus(statusData) {
  const s = statusData.status || 'idle';
  const pages = statusData.totalPages || 0;

  switch (s) {
    case 'finished':
      state.crawlFinished = true;
      stopTimer(); 
      DOM.graphLoading.classList.add('hidden');
      if (pages === 0) {
        setStatus('error', 'No pages crawled');
        showErrorBanner('Crawl finished but 0 pages were fetched.');
      } else {
        setStatus('idle', 'Finished (' + pages + ')');
      }
      break;
    case 'error':
      state.crawlFinished = true;
      stopTimer(); 
      setStatus('error', 'Crawl Error');
      if (statusData.error) showErrorBanner('Crawl error: ' + statusData.error);
      break;
    case 'running':
      setStatus('crawling', 'Crawling (' + pages + ')');
      break;
    default:
      setStatus('connecting', 'Waiting...');
      break;
  }
}

function applyGraphData(data) {
  if (!data || !Array.isArray(data.nodes) || !Array.isArray(data.edges)) return;

  const newNodes = [];
  const newEdges = [];

  for (const nodeEl of data.nodes) {
    if (!nodeEl.data?.id || state.knownNodeIds.has(nodeEl.data.id)) continue;
    state.knownNodeIds.add(nodeEl.data.id);
    newNodes.push({ group: 'nodes', data: { ...nodeEl.data, label: truncate(nodeEl.data.label, 40) } });
    if ((nodeEl.data.depth || 0) > state.maxDepthSeen) state.maxDepthSeen = nodeEl.data.depth;
    if (nodeEl.data.keywordMatch) state.matchCount++;
    try {
      const domain = new URL(nodeEl.data.id).hostname;
      state.domainMap.set(domain, (state.domainMap.get(domain) || 0) + 1);
    } catch (_) {}
  }

  for (const edgeEl of data.edges) {
    if (!edgeEl.data?.id || state.knownEdgeIds.has(edgeEl.data.id)) continue;
    if (!state.knownNodeIds.has(edgeEl.data.source) || !state.knownNodeIds.has(edgeEl.data.target)) continue;
    state.knownEdgeIds.add(edgeEl.data.id);
    newEdges.push({ group: 'edges', data: edgeEl.data });
    // track outgoing edges per source to compute maxBreadthSeen
    const src = edgeEl.data.source;
    const deg = (state.outDegree.get(src) || 0) + 1;
    state.outDegree.set(src, deg);
    if (deg > state.maxBreadthSeen) state.maxBreadthSeen = deg;
  }

  if (typeof data.nextSince === 'number') {
      state.graphSinceIndex = data.nextSince;
  }

  if (newNodes.length === 0 && newEdges.length === 0) return;

  if (newNodes.length === 0) {
    state.cy.add(newEdges);
    return;
  }

  updateStats();
  updateDomainList();

  newNodes.forEach((node, i) => {
    setTimeout(() => {
      state.cy.add(node);

      // FIX 8: Debounce layout repaints. Coalesce calculations to save CPU.
      if (i === newNodes.length - 1) {
        state.cy.add(newEdges);
        DOM.graphLoading.classList.add('hidden');
        if (_layoutDebounceTimer) clearTimeout(_layoutDebounceTimer);
        _layoutDebounceTimer = setTimeout(() => {
            runLayout();
            _layoutDebounceTimer = null;
        }, LAYOUT_DEBOUNCE_MS);
      }
    }, i * STAGGER_MS);
  });
}

function runLayout() {
  const cy = state.cy;
  const nodeCount = cy.nodes().length;

  // breadthfirst for ≤300 nodes: shows clear parent-child hierarchy, no overlap
  // fall back to cose for very large graphs where breadthfirst becomes unwieldy
  const layoutName = nodeCount <= 300 ? 'breadthfirst' : 'cose';

  if (layoutName === 'breadthfirst') {
    cy.layout({
      name:          'breadthfirst',
      animate:       true,
      animationDuration: 700,
      fit:           false,
      padding:       60,
      directed:      true,      // respect edge direction (parent → child)
      spacingFactor: 1.75,      // horizontal spread between siblings
      avoidOverlap:  true,
      nodeDimensionsIncludeLabels: false,
    }).run();
  } else {
    cy.layout({
      name:          'cose',
      animate:       true,
      animationDuration: 900,
      randomize:     !state.layoutRan,
      fit:           false,
      padding:       40,
      nodeRepulsion: () => 18000,
      idealEdgeLength: () => 120,
      edgeElasticity: () => 0.30,
      gravity:       0.15,
      numIter:       3000,
      componentSpacing: 100,
      nodeDimensionsIncludeLabels: true,
    }).run();
  }

  state.layoutRan = true;
}

function truncate(str, max) { return str && str.length > max ? str.substring(0, max - 3) + '...' : str || ''; }

function updateStats() {
  if (DOM.statPages)       DOM.statPages.textContent       = state.knownNodeIds.size;
  if (DOM.statDepth)       DOM.statDepth.textContent       = state.maxDepthSeen;
  if (DOM.statBreadthSeen) DOM.statBreadthSeen.textContent = state.maxBreadthSeen;
  if (state.keyword && DOM.statMatches) DOM.statMatches.textContent = state.matchCount;
}

// FIX 10: XSS Mitigation
function updateDomainList() {
  if (state.domainMap.size === 0) return;
  const empty = document.getElementById('domain-empty');
  if (empty) empty.remove();

  const existing = new Set(Array.from(DOM.domainList.querySelectorAll('.domain-item')).map(el => el.dataset.domain));

  for (const [domain, count] of state.domainMap.entries()) {
    if (existing.has(domain)) {
      DOM.domainList.querySelector(`[data-domain="${domain}"] .domain-count`).textContent = count;
      continue;
    }
    const li = document.createElement('li');
    li.className = 'domain-item flex justify-between py-2 border-b border-gray-200 label-mono hover:bg-gray-100 cursor-pointer px-2';
    li.dataset.domain = domain;
    
    const nameSpan  = document.createElement('span');
    nameSpan.className   = 'truncate pr-2';
    nameSpan.title       = domain;
    nameSpan.textContent = domain;

    const countSpan = document.createElement('span');
    countSpan.className   = 'domain-count font-bold';
    countSpan.textContent = String(count);

    li.appendChild(nameSpan);
    li.appendChild(countSpan);
    
    li.addEventListener('click', () => focusDomain(domain, li));
    DOM.domainList.appendChild(li);
  }
}

function focusDomain(domain, listItem) {
  const prev = DOM.domainList.querySelector('.active');
  if (prev) prev.classList.remove('active', 'bg-gray-200');
  if (state.activeDomain === domain) { state.activeDomain = null; resetHighlight(); return; }
  listItem.classList.add('active', 'bg-gray-200');
  state.activeDomain = domain;
  const nodes = state.cy.nodes().filter(n => { try { return new URL(n.id()).hostname === domain; } catch (_) { return false; } });
  if (nodes.length > 0) {
    state.cy.elements().addClass('dimmed');
    nodes.addClass('highlighted').removeClass('dimmed');
    nodes.neighborhood().addClass('highlighted').removeClass('dimmed');
    state.cy.animate({ fit: { eles: nodes, padding: 80 } }, { duration: 500 });
  }
}

function highlightNeighborhood(node) {
  resetHighlight();
  const hood = node.closedNeighborhood();
  state.cy.elements().not(hood).addClass('dimmed');
  hood.addClass('highlighted').removeClass('dimmed');
}

function resetHighlight() { state.cy.elements().removeClass('dimmed highlighted'); }

function openDrawer(node) {
  const d = node.data();
  DOM.drawerDepth.textContent   = 'Depth ' + (d.depth != null ? d.depth : '?');
  DOM.drawerTitle.textContent   = d.label || d.id;
  DOM.drawerUrl.textContent     = d.id;
  DOM.drawerUrl.href            = d.id;
  DOM.metaFetch.textContent     = d.fetchTimeMs != null ? d.fetchTimeMs + 'ms' : '';
  DOM.metaKeyword.textContent   = d.keywordMatch ? 'Yes' : '';
  DOM.metaTimestamp.textContent = d.timestamp ? new Date(d.timestamp).toLocaleTimeString() : '';
  const parentNode = node.incomers('node').first();
  DOM.metaParent.textContent    = parentNode.length ? parentNode.id() : 'Root (seed URL)';
  
  DOM.drawer.classList.remove('hidden');

  state.currentNodeUrl = d.id;
  
  // FIX 9: Replaced node cloning with graceful AbortController for event listeners
  if (state.viewDataAbort) state.viewDataAbort.abort();
  state.viewDataAbort = new AbortController();
  if (DOM.btnViewData) {
    DOM.btnViewData.addEventListener('click', openModal, { signal: state.viewDataAbort.signal });
  }
}

function closeDrawer() {
  DOM.drawer.classList.add('hidden');
  resetHighlight();
  state.currentNodeUrl = null;
}

async function openModal() {
  if (!state.currentNodeUrl || !DOM.dataModal) return;
  DOM.modalUrlDisplay.textContent = truncate(state.currentNodeUrl, 60);
  DOM.modalText.textContent = 'Loading...';
  DOM.dataModal.classList.remove('hidden');

  try {
    const encUrl = encodeURIComponent(state.currentNodeUrl);
    const resp = await fetch(API_PAGE_URL + '?url=' + encUrl);
    if (!resp.ok) {
      const errJson = await resp.json().catch(() => ({}));
      DOM.modalText.textContent = errJson.error || 'HTTP ' + resp.status;
      return;
    }
    const data = await resp.json();
    DOM.modalText.textContent = data.text;
  } catch (err) {
    DOM.modalText.textContent = 'Failed to load page text.';
  }
}

function closeModal() {
  if (DOM.dataModal) DOM.dataModal.classList.add('hidden');
}

function setStatus(cssClass, label) {
  DOM.statusDot.className    = 'status-dot ' + cssClass;
  DOM.statusText.textContent = label;
}

function showErrorBanner(msg) { DOM.errorMessage.textContent = msg; DOM.errorBanner.classList.remove('hidden'); }
function hideErrorBanner()    { DOM.errorBanner.classList.add('hidden'); }

document.addEventListener('DOMContentLoaded', () => {
  initDOMRefs();
  initSetupForm();
  DOM.drawerClose.addEventListener('click', () => { closeDrawer(); resetHighlight(); });

  if (DOM.btnModalClose) {
    DOM.btnModalClose.addEventListener('click', closeModal);
  }
  if (DOM.dataModal) {
    DOM.dataModal.addEventListener('click', (e) => {
      if (e.target === DOM.dataModal) closeModal();
    });
  }
});