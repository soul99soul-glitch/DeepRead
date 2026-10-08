const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { method, entryRoot, loadPureModule, domainRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { DEEPREAD_DISCOVERY_SOURCES } = loadPureModule(path.join(domainRoot, 'discovery.ts'));
const source = name => fs.readFileSync(path.join(entryRoot, 'pages', name), 'utf8');
const boardSource = () => source('BoardPage.ets').slice(source('BoardPage.ets').indexOf('export struct BoardPageContent'));
function controller(text, names, env) {
  const code = ts.transpileModule('class Page {\n' + names.map(name => method(text, name)).join('\n') + '\n} return new Page();',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function(...Object.keys(env), code)(...Object.values(env));
}
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
const tick = () => new Promise(resolve => setImmediate(resolve));

function board() {
  const loads = [], gate = deferred();
  const page = controller(boardSource(), ['aboutToAppear', 'onPageShow', 'onPageHide', 'loadHotlist', 'stopRefresh'], {
    getProductKind: () => 'deepread', createEntryAbortController: () => ({ signal: { aborted: false }, abort() { this.signal.aborted = true; } }),
    getAppContainer: () => ({ storage: { get: async (key, fallback) => { loads.push(key); await gate.promise; return fallback; } } }),
    HOTLIST_PRESETS: [], HOTLIST_CACHE_KEY: 'cache', parseHotListFocusKeywords: value => value ? [value] : [],
    shouldRefreshHotList: () => false,
  });
  Object.assign(page, { embeddedRoot: true, rootVisible: false, pageVisible: false, loading: false,
    refreshing: false, refreshToken: 0, requestController: null, cacheLoaded: false, rawSections: [],
    runObservationToken: 0, unsubscribeRuns: null, onBackHandler() {}, dockScroll: { reset() {} },
    observeRuns() {}, loadIssue() {}, saveDiscoveryScroll() {}, settleArrival() {}, applyProjection() {} });
  return { page, loads, gate };
}

test('hidden discovery mount loads on its first visible transition and reloads after cancellation', async () => {
  const f = board(); f.page.aboutToAppear(); assert.equal(f.loads.length, 0);
  f.page.rootVisible = true; f.page.onPageShow();
  assert.equal(f.loads.length, 1, 'first showing a hidden retained discovery root must load preferences and hotlist');
  const first = f.page.requestController;
  f.page.onPageHide(); assert.equal(first.signal.aborted, true); assert.equal(f.page.loading, false);
  f.page.onPageShow(); assert.equal(f.loads.length, 2);
  f.gate.resolve(); await tick(); assert.equal(f.page.loading, false);
});

test('initially visible discovery mount uses its existing loading gate to prevent duplicate work', async () => {
  const f = board(); f.page.rootVisible = true; f.page.aboutToAppear();
  assert.equal(f.loads.length, 1); f.gate.resolve(); await tick();
  assert.equal(f.loads.length, 6); assert.equal(f.page.loading, false);
});

test('browser launch failures report the failed action without claiming a clipboard write', async () => {
  for (const synchronous of [false, true]) {
    const calls = [], toasts = [];
    const page = controller(boardSource(), ['openOriginal'], {
      getContext: () => ({ startAbility: want => { calls.push(want); if (synchronous) throw Error('browser unavailable'); return Promise.reject(Error('browser unavailable')); } }),
      promptAction: { showToast: toast => toasts.push(toast.message) },
    });
    page.openOriginal('https://news.test/item'); await tick();
    assert.equal(calls.length, 1); assert.deepEqual(toasts, ['无法打开浏览器，请稍后重试']);
  }
  const toasts = [];
  const page = controller(boardSource(), ['openOriginal'], {
    getContext: () => ({ startAbility: async () => {} }), promptAction: { showToast: toast => toasts.push(toast) },
  });
  page.openOriginal('https://news.test/item'); await tick(); assert.equal(toasts.length, 0);
});

function settings(storage) {
  const routes = [], toasts = [];
  const page = controller(source('DeepReadDiscoverySettingsPage.ets'),
    ['persistPreference', 'loadSettings', 'canLeave', 'goBack', 'onBackPress'], {
      getProductKind: () => 'deepread', getAppContainer: () => ({ storage }), DEEPREAD_DISCOVERY_SOURCES,
      DeepReadHaptics: { selection() {} }, router: { back: () => routes.push('back') },
      promptAction: { showToast: value => toasts.push(value.message) },
    });
  Object.assign(page, { alive: true, settingsLoaded: true, loading: false, saving: false, loadToken: 1,
    error: '', notice: '', writeFailure: '', pendingWrites: 0, writeQueue: Promise.resolve() });
  return { page, routes, toasts };
}

test('all discovery exit paths wait for the queued latest preference before returning', async () => {
  const values = new Map(), firstFlush = deferred(); let writes = 0;
  const f = settings({ set: async (key, value) => { values.set(key, value); if (++writes === 1) await firstFlush.promise; } });
  f.page.persistPreference('deepread_focus_keywords', 'A'); f.page.persistPreference('deepread_focus_keywords', 'AI');
  await tick(); assert.equal(values.get('deepread_focus_keywords'), 'A');
  assert.equal(f.page.onBackPress(), true); f.page.goBack(); assert.equal(f.routes.length, 0);
  assert.equal(f.page.canLeave(), false, 'Dock must be denied while latest settings are queued');
  assert.ok(f.toasts.every(message => /正在保存/.test(message)));
  firstFlush.resolve(); await f.page.writeQueue;
  assert.equal(values.get('deepread_focus_keywords'), 'AI'); assert.equal(f.page.pendingWrites, 0);
  assert.equal(f.page.onBackPress(), false); assert.equal(f.page.canLeave(), true);
  f.page.goBack(); assert.deepEqual(f.routes, ['back']);
  const text = source('DeepReadDiscoverySettingsPage.ets');
  assert.match(text, /DeepReadSettingsHeader\(\{[^}]*onBack:[^}]*this\.goBack\(\)/);
  assert.match(text, /DeepReadSubpageDock\(\{[^}]*onBeforeLeave:[^}]*this\.canLeave\(\)/);
});

test('failed discovery writes finish the existing reload and leave the error visible before exit is allowed', async () => {
  const reread = deferred();
  const f = settings({ set: async () => { throw Error('disk'); }, get: async (_key, fallback) => { await reread.promise; return fallback; } });
  f.page.persistPreference('deepread_focus_keywords', 'AI'); await tick();
  assert.equal(f.page.pendingWrites, 0); assert.equal(f.page.canLeave(), false, 'saving also covers failure reload');
  reread.resolve(); await f.page.writeQueue;
  assert.match(f.page.error, /保存发现设置失败.*disk/); assert.equal(f.page.focusKeywords, '');
  assert.equal(f.page.canLeave(), true);
});
