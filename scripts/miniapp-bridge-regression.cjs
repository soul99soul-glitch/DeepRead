#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const bridgeSource = fs.readFileSync(
  path.resolve(__dirname, '../app/src/main/assets/miniapp/miniapp_bridge.js'), 'utf8',
);

function openBridge() {
  const timers = new Map();
  const requests = [];
  let now = 0;
  let nextTimerId = 1;
  const window = {
    __AMBER_MINIAPP_SESSION_TOKEN__: 'runner-token',
    setTimeout(callback, delay) {
      const id = nextTimerId++;
      timers.set(id, { callback, deadline: now + delay });
      return id;
    },
    clearTimeout(id) { timers.delete(id); },
    AmberNative: { postMessage(raw) { requests.push(JSON.parse(raw)); } },
  };
  vm.runInNewContext(bridgeSource, { window });
  return {
    amber: window.Amber,
    requests,
    advance(milliseconds) {
      const target = now + milliseconds;
      while (true) {
        const next = [...timers.entries()]
          .filter(([, timer]) => timer.deadline <= target)
          .sort((a, b) => a[1].deadline - b[1].deadline)[0];
        if (!next) break;
        timers.delete(next[0]);
        now = next[1].deadline;
        next[1].callback();
      }
      now = target;
    },
    respond(response) {
      window.AmberBridge._handleNativeResponse({ id: requests.at(-1).id, ...response });
    },
  };
}

function track(promise) {
  const outcome = { state: 'pending' };
  promise.then(
    value => Object.assign(outcome, { state: 'fulfilled', value }),
    error => Object.assign(outcome, { state: 'rejected', error }),
  );
  return outcome;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

// These methods can wait in a real native user-confirmation dialog. A JS
// deadline cannot distinguish reading that dialog from a failed operation.
const confirmedCalls = [
  ['launch', amber => amber.launch({ appId: 'other-app' })],
  ['sensor.subscribe', amber => amber.sensor.subscribe({ type: 'light' }, () => {})],
  ['clipboard.read', amber => amber.clipboard.read()],
  ['location.getCurrent', amber => amber.location.getCurrent({ accuracy: 'coarse' })],
  ['host.getConversationContext', amber => amber.host.getConversationContext({})],
  ['host.sendToConversation', amber => amber.host.sendToConversation({ conversationId: 'chat', text: 'hello', mode: 'send' })],
  ['host.createArtifact', amber => amber.host.createArtifact({ title: 'note', content: 'hello' })],
  ['ai.generate', amber => amber.ai.generate({ prompt: 'hello' })],
  ['haptics.impact', amber => amber.haptics.impact({ style: 'light' })],
  ['haptics.notification', amber => amber.haptics.notification({ type: 'success' })],
  ['haptics.selection', amber => amber.haptics.selection()],
  ['device.getInfo', amber => amber.device.getInfo()],
  ['device.getBattery', amber => amber.device.getBattery()],
  ['screen.getBrightness', amber => amber.screen.getBrightness()],
  ['screen.setBrightness', amber => amber.screen.setBrightness(0.5)],
  ['screen.setKeepAwake', amber => amber.screen.setKeepAwake(true)],
  ['speech.getVoices', amber => amber.speech.getVoices()],
  ['speech.speak', amber => amber.speech.speak('hello')],
  ['speech.stop', amber => amber.speech.stop()],
  ['speech.pause', amber => amber.speech.pause()],
  ['speech.resume', amber => amber.speech.resume()],
  ['share', amber => amber.share({ text: 'hello' })],
  ['openURL', amber => amber.openURL('https://example.com/')],
];

test('user-confirmed calls retain their receipt while the user reads the dialog', async () => {
  for (const [method, invoke] of confirmedCalls) {
    const bridge = openBridge();
    const outcome = track(invoke(bridge.amber));
    assert.equal(bridge.requests[0].method, method);
    assert.equal(bridge.requests[0].token, 'runner-token');

    bridge.advance(120_000);
    await flushPromises();
    assert.equal(outcome.state, 'pending', `${method} must wait for the native decision`);

    bridge.respond({ ok: true, data: { accepted: true, subscriptionId: 'sensor-1' } });
    await flushPromises();
    assert.equal(outcome.state, 'fulfilled', `${method} must deliver the delayed native receipt`);
    if (method === 'sensor.subscribe') {
      assert.equal(outcome.value.subscriptionId, 'sensor-1');
      const unsubscribe = outcome.value.unsubscribe();
      assert.equal(bridge.requests.at(-1).method, 'sensor.unsubscribe');
      assert.equal(bridge.requests.at(-1).params.subscriptionId, 'sensor-1');
      bridge.respond({ ok: true, data: true });
      await unsubscribe;
    } else {
      assert.equal(outcome.value.accepted, true);
    }
  }
});

test('a delayed user denial retains the native error code', async () => {
  for (const [method, invoke] of confirmedCalls) {
    const bridge = openBridge();
    const outcome = track(invoke(bridge.amber));
    bridge.advance(120_000);
    await flushPromises();
    bridge.respond({ ok: false, error: 'User denied MiniApp request', errorCode: 'user_denied' });
    await flushPromises();
    assert.equal(outcome.state, 'rejected', method);
    assert.equal(outcome.error.code, 'user_denied', `${method} must report the actual decision`);
  }
});

test('noninteractive fetch and storage calls retain their existing deadlines', async () => {
  const bridge = openBridge();
  const fetch = track(bridge.amber.fetch({ url: 'https://example.com/api' }));
  const storage = track(bridge.amber.storage.get('key'));
  bridge.advance(4_999);
  await flushPromises();
  assert.equal(storage.state, 'pending');
  bridge.advance(1);
  await flushPromises();
  assert.equal(storage.state, 'rejected');
  assert.equal(storage.error.message, 'MiniApp bridge timeout');
  assert.equal(fetch.state, 'pending');
  bridge.advance(10_000);
  await flushPromises();
  assert.equal(fetch.state, 'rejected');
  assert.equal(fetch.error.message, 'MiniApp bridge timeout');
});
