const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const { entryRoot, loadPureModule, actualPage } = require('./deepread_ui_fixture.cjs');

const parser = loadPureModule(path.resolve(__dirname, '../../../chat/src/main/ets/chat/markdown_blocks.ts'));
const { deepReadMarkdownHtml } = loadPureModule(path.join(entryRoot, 'platform_impl/DeepReadMarkdownHtml.ets'));
// Exercise the production TXT formatter without invoking SDK file/share APIs.
const exportFile = path.join(entryRoot, 'platform_impl/DeepReadExportFiles.ets');
const textExports = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(exportFile, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: textExports, require: spec => {
  if (spec === '@amber/chat-domain') return parser;
  if (spec.startsWith('@kit.')) return {};
  throw Error('unexpected export module ' + spec);
} }, { filename: exportFile });

test('DeepRead Web and TXT retain balanced parentheses in report destinations', () => {
  const url = 'https://example.com/report_(2026)?q=(outer(inner))&source=reader';
  const source = `[报告](${url})`;
  assert.equal(deepReadMarkdownHtml(source), '<p><a href="https://example.com/report_(2026)?q=(outer(inner))&amp;source=reader">报告</a></p>');
  assert.equal(textExports.deepReadMarkdownToText(source), `报告 (${url})`);
  const native = actualPage('components/NativeMarkdownText.ets', ['inlineRuns'], {});
  native.inlines = parser.parseInline(source);
  assert.deepEqual(native.inlineRuns(), [{ kind: 'link', text: '报告', url }]);
});

test('DeepRead Web and TXT unwrap angle destinations for links and images', () => {
  const source = '[来源](<https://example.com/source_(2026)>) ![图](<https://example.com/photo_(2026).png>)';
  assert.equal(deepReadMarkdownHtml(source), '<p><a href="https://example.com/source_(2026)">来源</a> <img class="inline" src="https://example.com/photo_(2026).png" alt="图"/></p>');
  assert.equal(textExports.deepReadMarkdownToText(source), '来源 (https://example.com/source_(2026)) 图 (https://example.com/photo_(2026).png)');
});

test('DeepRead retains code spans and blocks unsafe link/image destinations', () => {
  assert.equal(deepReadMarkdownHtml('`[来源](https://example.com/report_(2026))`'), '<p><code>[来源](https://example.com/report_(2026))</code></p>');
  const html = deepReadMarkdownHtml('[来源](<javascript:alert(1)>) ![图](javascript:alert(1))');
  assert.equal(html.includes('<a '), false);
  assert.equal(html.includes('<img '), false);
});

test('DeepRead retains the source anchor following malformed destinations or code examples', () => {
  for (const prefix of ['[坏](<https://bad.example)', '[坏](https://bad.example/path_(oops)', '`[坏](<https://bad.example)`']) {
    const source = `${prefix} [来源](https://example.com/source)`;
    assert.ok(deepReadMarkdownHtml(source).includes('<a href="https://example.com/source">来源</a>'));
    assert.ok(textExports.deepReadMarkdownToText(source).includes('来源 (https://example.com/source)'));
  }
});
