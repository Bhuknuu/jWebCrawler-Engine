/**
 * app.js — jWebCrawler Dashboard — Frontend Logic (v2)
 */

'use strict';

const API_GRAPH_URL   = '/api/graph';
const API_STATUS_URL  = '/api/status';
const API_START_URL   = '/api/start';
const API_RESET_URL   = '/api/reset';
const POLL_INTERVAL   = 3000;
const MAX_BACKOFF     = 30000;
const NODE_LIMIT_LAYOUT = 500;

const TOKENS = {
  bgBase:       '#ffffff',
  nodeDefault:  '#111111',
  nodeHighlight:'#0044CC',
  nodeMatch:    '#D90036',
  edgeColor:    '#cccccc',
  edgeHighlight:'#0044CC',
  textPrimary:  '#ffffff',
  accentColor:  '#D90036',
};

const DOM = {};

function initDOMRefs() {
  DOM.setupOverlay  = document.getElementById('view-setup');
  DOM.setupForm     = document.getElementById('setup-form');
  DOM.inputSeedUrl  = document.getElementById('seed-url');
  DOM.inputMaxDepth = document.getElementById('max-depth');
  DOM.inputMaxPages = document.getElementById('max-pages');
  DOM.inputKeyword  = document.getElementById('keyword');
  DOM.formError     = document.getElementById('form-error');
  DOM.btnStart      = document.getElementById('btn-start-crawl');
  
  DOM.app           = document.getElementById('app');
  DOM.statusDot     = document.getElementById('status-dot');
  DOM.statusText    = document.getElementById('status-text');
  DOM.btnReset      = document.getElementById('btn-reset');
  
  DOM.statNodes     = document.getElementById('stat-nodes');
  DOM.statEdges     = document.getElementById('stat-edges');
  DOM.statDepth     = document.getElementById('stat-depth');
  DOM.statMatches   = document.getElementById('stat-matches');
  
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
}

let state = getInitialState();

function getInitialState() {
  return {
    knownNodeIds:  new Set(),
    knownEdgeIds:  new Set(),
    domainMap:     new Map(),
    failCount:     0,
    pollTimer:     null,
    maxDepthSeen:  0,
    matchCount:    0,
    cy:            null,
    activeDomain:  null,
    crawlFinished: false,
  };
}

function initSetupForm() {
  DOM.setupForm.addEventListener('submit', async function(e) {
    e.preventDefault();
    clearFormError();

    const seedUrl  = DOM.inputSeedUrl.value.trim();
    const maxDepth = parseInt(DOM.inputMaxDepth.value, 10);
    const maxPages = parseInt(DOM.inputMaxPages.value, 10);
    const keyword  = DOM.inputKeyword.value.trim();

    if (!seedUrl) return showFormError('Seed URL is required.');
    if (!seedUrl.startsWith('http://') && !seedUrl.startsWith('https://')) return showFormError('Seed URL must start with http:// or https://');

    setButtonLoading(true);

    try {
      const response = await fetch(API_START_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ seedUrl, maxDepth, keyword, maxPages }),
      });

      let data = {};
      try { data = await response.json(); } catch (_) {}

      if (response.status === 202) {
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
    try {
      await fetch(API_RESET_URL, { method: 'POST' });
    } catch(e) {}
    if (state.cy) state.cy.destroy();
    if (state.pollTimer) clearTimeout(state.pollTimer);
    state = getInitialState();
    DOM.app.style.display = 'none';
    DOM.setupOverlay.classList.remove('hidden');
    setButtonLoading(false);
    DOM.statNodes.textContent = '0';
    DOM.statEdges.textContent = '0';
    DOM.statDepth.textContent = '0';
    DOM.statMatches.textContent = '0';
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
  setTimeout(() => {
    DOM.setupOverlay.classList.add('hidden');
    initCytoscape();
    startPolling();
  }, 400);
}

function initCytoscape() {
  state.cy = cytoscape({
    container: document.getElementById('cy'),
    elements: [],
    style: buildCytoscapeStyle(),
    layout: { name: 'preset' },
    minZoom: 0.1, maxZoom: 3,
    userZoomingEnabled: true, userPanningEnabled: true, boxSelectionEnabled: false,
  });

  state.cy.on('tap', 'node', function(evt) { openDrawer(evt.target); highlightNeighborhood(evt.target); });
  state.cy.on('tap', function(evt) { if (evt.target === state.cy) { closeDrawer(); resetHighlight(); } });
}

function buildCytoscapeStyle() {
  return [
    {
      selector: 'node',
      style: {
        'background-color':  TOKENS.nodeDefault,
        'width': 20, 'height': 20, 'label': '',
        'font-family': 'IBM Plex Mono, monospace', 'font-size': 12, 'font-weight': 'bold',
        'color': '#000',
        'text-valign': 'bottom', 'text-halign': 'center', 'text-margin-y': 4,
        'text-background-color': '#fff', 'text-background-opacity': 0.8,
        'transition-property': 'background-color, width, height', 'transition-duration': '0.2s',
      }
    },
    { selector: 'node[?keywordMatch]', style: { 'background-color': TOKENS.nodeMatch, 'width': 28, 'height': 28 } },
    { selector: 'node:selected, node.highlighted', style: { 'background-color': TOKENS.nodeHighlight, 'label': 'data(label)', 'width': 30, 'height': 30 } },
    { selector: 'node.dimmed',     style: { 'opacity': 0.1 } },
    { selector: 'node[depth = 0]', style: { 'background-color': TOKENS.accentColor, 'width': 36, 'height': 36, 'shape': 'star' } },
    {
      selector: 'edge',
      style: {
        'width': 2, 'line-color': TOKENS.edgeColor, 'target-arrow-color': TOKENS.edgeColor,
        'target-arrow-shape': 'triangle', 'arrow-scale': 1, 'curve-style': 'bezier',
      }
    },
    { selector: 'edge.highlighted', style: { 'line-color': TOKENS.edgeHighlight, 'target-arrow-color': TOKENS.edgeHighlight, 'width': 3 } },
    { selector: 'edge.dimmed',      style: { 'opacity': 0.1 } },
  ];
}

function startPolling() {
  setStatus('crawling', 'Running');
  state.pollTimer = setTimeout(fetchAndUpdate, 1500);
}

async function fetchAndUpdate() {
  if (state.crawlFinished) return;

  try {
    const [graphResp, statusResp] = await Promise.all([
      fetch(API_GRAPH_URL, { cache: 'no-store' }),
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

function syncStatus(statusData) {
  const s = statusData.status || 'idle';
  const pages = statusData.totalPages || 0;

  switch (s) {
    case 'finished':
      state.crawlFinished = true;
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

  const cy = state.cy;
  const newElements = [];
  let hasNewNodes = false;

  for (const nodeEl of data.nodes) {
    if (!nodeEl.data || !nodeEl.data.id || state.knownNodeIds.has(nodeEl.data.id)) continue;
    state.knownNodeIds.add(nodeEl.data.id);
    hasNewNodes = true;
    newElements.push({ group: 'nodes', data: { ...nodeEl.data, label: truncate(nodeEl.data.label, 40) } });
    if ((nodeEl.data.depth || 0) > state.maxDepthSeen) state.maxDepthSeen = nodeEl.data.depth;
    if (nodeEl.data.keywordMatch) state.matchCount++;
    try {
      const domain = new URL(nodeEl.data.id).hostname;
      state.domainMap.set(domain, (state.domainMap.get(domain) || 0) + 1);
    } catch (_) {}
  }

  for (const edgeEl of data.edges) {
    if (!edgeEl.data || !edgeEl.data.id || state.knownEdgeIds.has(edgeEl.data.id)) continue;
    if (!state.knownNodeIds.has(edgeEl.data.source) || !state.knownNodeIds.has(edgeEl.data.target)) continue;
    state.knownEdgeIds.add(edgeEl.data.id);
    newElements.push({ group: 'edges', data: edgeEl.data });
  }

  if (newElements.length === 0) return;
  cy.add(newElements);
  updateStats();
  updateDomainList();
  if (hasNewNodes) { runLayout(); DOM.graphLoading.classList.add('hidden'); }
}

function runLayout() {
  const cy = state.cy;
  // Bouncy top-to-bottom layout using cytoscape-cola
  cy.layout({ 
    name: 'cola', 
    animate: true, 
    refresh: 2, 
    maxSimulationTime: 2000,
    randomize: false, 
    fit: true,
    padding: 30,
    nodeSpacing: 15,
    edgeLength: 40,
    flow: { axis: 'y', minSeparation: 60 } // Top to bottom flow!
  }).run();
}

function truncate(str, max) { return str && str.length > max ? str.substring(0, max - 3) + '...' : str || ''; }

function updateStats() {
  DOM.statNodes.textContent   = state.knownNodeIds.size;
  DOM.statEdges.textContent   = state.knownEdgeIds.size;
  DOM.statDepth.textContent   = state.maxDepthSeen;
  DOM.statMatches.textContent = state.matchCount;
}

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
    li.innerHTML = `<span class="truncate pr-2" title="${domain}">${domain}</span><span class="domain-count font-bold">${count}</span>`;
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
  DOM.metaFetch.textContent     = d.fetchTimeMs != null ? d.fetchTimeMs + 'ms' : '\u2014';
  DOM.metaKeyword.textContent   = d.keywordMatch ? 'Yes' : 'No';
  DOM.metaTimestamp.textContent = d.timestamp ? new Date(d.timestamp).toLocaleTimeString() : '\u2014';
  const parentNode = node.incomers('node').first();
  DOM.metaParent.textContent    = parentNode.length ? parentNode.id() : 'Root (seed URL)';
  DOM.drawer.classList.remove('hidden');
}

function closeDrawer() { DOM.drawer.classList.add('hidden'); }

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
});
