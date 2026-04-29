'use strict';

const API_GRAPH_URL   = '/api/graph';
const API_STATUS_URL  = '/api/status';
const API_START_URL   = '/api/start';
const API_RESET_URL   = '/api/reset';
const API_PAGE_URL    = '/api/page';    
const POLL_INTERVAL   = 3000;
const MAX_BACKOFF     = 30000;
const STAGGER_MS      = 55;
const LAYOUT_DEBOUNCE_MS = 800;  // FIX 8: Debounce layout runs

const DEPTH_PALETTE = [
  '#ffffff',                   
  'rgba(200,220,255,0.85)',    
  'rgba(160,185,255,0.78)',    
  'rgba(130,150,240,0.72)',    
  'rgba(180,100,210,0.72)',    
  'rgba(220,70,130,0.78)',     
  '#D90036',                   
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
  DOM.statThroughput= document.getElementById('stat-throughput'); 

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
    matchCount:         0,
    cy:                 null,
    activeDomain:       null,
    crawlFinished:      false,
    currentNodeUrl:     null,   
    graphSinceIndex:    0,      // FIX 3: Graph Delta Tracking
    viewDataAbort:      null,   // FIX 9: Event Listener Management
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
        body: JSON.stringify({ seedUrl, maxDepth, keyword, maxPages, maxBreadth }),
      });

      let data = {};
      try { data = await response.json(); } catch (_) {}

      if (response.status === 202) {
        state.maxDepth   = maxDepth;
        state.maxPages   = maxPages;
        state.maxBreadth = maxBreadth;
        if (DOM.statPagesMax)  DOM.statPagesMax.textContent  = maxPages;
        if (DOM.statDepthMax)  DOM.statDepthMax.textContent  = maxDepth;
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
    if (state.cy)           state.cy.destroy();
    if (state.pollTimer)    clearTimeout(state.pollTimer);
    if (state.timerInterval) clearInterval(state.timerInterval); 
    closeModal();
    state = getInitialState();
    DOM.app.style.display = 'none';
    DOM.setupOverlay.classList.remove('hidden');
    setButtonLoading(false);
    
    if (DOM.statPages)     DOM.statPages.textContent      = '';
    if (DOM.statPagesMax)  DOM.statPagesMax.textContent   = '';
    if (DOM.statEdges)     DOM.statEdges.textContent      = '';
    if (DOM.statDepth)     DOM.statDepth.textContent      = '';
    if (DOM.statDepthMax)  DOM.statDepthMax.textContent   = '';
    if (DOM.statMatches)   DOM.statMatches.textContent    = '';
    if (DOM.statTimer)     DOM.statTimer.textContent      = '';
    if (DOM.statThroughput)DOM.statThroughput.textContent = '';
    DOM.domainList.innerHTML = '<li class="domain-item--empty label-mono" id="domain-empty">Awaiting crawl data&hellip;</li>';
    DOM.graphLoading.classList.remove('hidden');
    hideErrorBanner();
    closeDrawer();
  });
  
  DOM.btnCloseError.addEventListener('click', hideErrorBanner);
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
        DOM.tooltip.style.left = (x + 14) + 'px';
        DOM.tooltip.style.top  = (y - 10) + 'px';
        _tooltipRafId = null;
    });
  });
  document.getElementById('cy').style.cursor = 'crosshair';
  state.cy.on('mouseover', 'node', function() { document.getElementById('cy').style.cursor = 'pointer'; });
  state.cy.on('mouseout',  'node', function() { document.getElementById('cy').style.cursor = 'crosshair'; });
}

function buildCytoscapeStyle() {
  return [
    {
      selector: 'node',
      style: {
        'background-color':  node => {
          const d = node.data('depth') || 0;
          return DEPTH_PALETTE[Math.min(d, DEPTH_PALETTE.length - 1)];
        },
        'background-opacity': 0.8,
        'border-width':      0,
        'width':  6, 'height': 6,
        'label':  '',
        'overlay-opacity': 0,
        'transition-property': 'background-color, width, height, background-opacity',
        'transition-duration': '0.25s',
      }
    },
    {
      selector: 'node[?keywordMatch]',
      style: {
        'background-color':  TOKENS.nodeMatch,
        'background-opacity': 1,
        'width': 9, 'height': 9,
      }
    },
    {
      selector: 'node:selected, node.highlighted',
      style: {
        'background-color':  TOKENS.nodeHighlight,
        'background-opacity': 1,
        'width': 10, 'height': 10,
        'label': 'data(label)',
        'font-family': 'IBM Plex Mono, monospace',
        'font-size': 9,
        'color': TOKENS.labelColor,
        'text-valign': 'bottom', 'text-halign': 'center',
        'text-margin-y': 4,
        'text-background-color': TOKENS.bgBase,
        'text-background-opacity': 0.7,
        'text-background-padding': '2px',
      }
    },
    { selector: 'node.dimmed',     style: { 'opacity': 0.08 } },
    {
      selector: 'node[depth = 0]',
      style: {
        'width': 14, 'height': 14,
        'label': 'data(label)',
        'font-family': 'IBM Plex Mono, monospace',
        'font-size': 9,
        'color': TOKENS.labelColor,
        'text-valign': 'bottom', 'text-halign': 'center',
        'text-margin-y': 4,
        'text-background-color': TOKENS.bgBase,
        'text-background-opacity': 0.7,
        'text-background-padding': '2px',
      }
    },
    {
      selector: 'edge',
      style: {
        'width': 0.5,
        'line-color': TOKENS.edgeColor,
        'target-arrow-color': TOKENS.edgeColor,
        'target-arrow-shape': 'none',
        'curve-style': 'haystack',
        'opacity': 1,
      }
    },
    {
      selector: 'edge.highlighted',
      style: {
        'line-color': TOKENS.edgeHighlight,
        'target-arrow-color': TOKENS.edgeHighlight,
        'target-arrow-shape': 'triangle',
        'arrow-scale': 0.5,
        'width': 1,
        'opacity': 0.7,
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
  const extent = cy.extent();
  const cx = (extent.x1 + extent.x2) / 2;
  const cy_ = (extent.y1 + extent.y2) / 2;

  cy.nodes().filter(n => !n.position().x && !n.position().y).forEach(n => {
    n.position({
      x: cx + (Math.random() - 0.5) * 120,
      y: cy_ + (Math.random() - 0.5) * 120,
    });
  });

  cy.layout({
    name:             'cose',
    animate:          true,
    animationDuration:900,
    randomize:        false,   
    fit:              false,   
    padding:          30,
    nodeRepulsion:    () => 4500,
    idealEdgeLength:  () => 60,
    edgeElasticity:   () => 0.45,
    gravity:          0.25,
    numIter:          1800,    
  }).run();
}

function truncate(str, max) { return str && str.length > max ? str.substring(0, max - 3) + '...' : str || ''; }

function updateStats() {
  if (DOM.statPages)   DOM.statPages.textContent   = state.knownNodeIds.size;
  if (DOM.statEdges)   DOM.statEdges.textContent   = state.knownEdgeIds.size;
  if (DOM.statDepth)   DOM.statDepth.textContent   = state.maxDepthSeen;
  if (DOM.statMatches) DOM.statMatches.textContent = state.matchCount;
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