const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl');
const load = (filename, imports, globals = {}) => {
  const exports = {};
  vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText, { exports, require: spec => {
    if (Object.hasOwn(imports, spec)) return imports[spec];
    throw new Error(`unexpected import ${spec}`);
  }, Error, Promise, String, Date, Math, Uint8Array, ArrayBuffer, ...globals }, { filename });
  return exports;
};

// Execute both production Entry modules and the actual domain normalizer. Only
// SDK services are injected; preview temp files are written/read on the OS.
const fixture = pages => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'deepread-pdf-'));
  const selected = path.join(dir, 'selected.pdf');
  fs.writeFileSync(selected, '%PDF-fixture');
  const calls = { pages: [], text: [], releases: [], yields: 0, documentsReleased: 0 };
  let activePages = 0;
  const fileIo = {
    OpenMode: { READ_ONLY: 1, READ_WRITE: 2, CREATE: 4, TRUNC: 8 },
    openSync: (filename, mode) => ({ fd: fs.openSync(filename, mode === 1 ? 'r' : 'w+') }),
    statSync: fd => fs.fstatSync(fd),
    readSync: (fd, bytes, options) => fs.readSync(fd, new Uint8Array(bytes), 0, options.length, options.offset),
    writeSync: (fd, bytes) => fs.writeSync(fd, new Uint8Array(bytes)),
    closeSync: file => fs.closeSync(file.fd),
    accessSync: filename => fs.existsSync(filename),
    unlinkSync: filename => fs.unlinkSync(filename),
  };
  const pdfService = {
    ParseResult: { PARSE_SUCCESS: 0, PARSE_ERROR_PASSWORD: 1 },
    GraphicsObjectType: { OBJECT_TEXT: 1 },
    PdfDocument: class {
      loadDocument(filename) { assert.equal(fs.readFileSync(filename, 'utf8'), '%PDF-fixture'); return 0; }
      getPageCount() { return pages.length; }
      getPage(index) {
        assert.equal(activePages, 0, 'the previous native page must be released');
        activePages++; calls.pages.push(index);
        return {
          getGraphicsObjects: () => pages[index].map((text, objectIndex) => text === null ? { type: 2 } : {
            type: 1, get text() { calls.text.push([index, objectIndex]); return text; },
          }),
          release: () => { activePages--; calls.releases.push(index); },
        };
      }
      releaseDocument() { assert.equal(activePages, 0); calls.documentsReleased++; }
    },
  };
  let sequence = 0;
  const parser = load(path.join(ENTRY, 'PdfDocumentParser.ets'), {
    '@kit.PDFKit': { pdfService }, '@kit.CoreFileKit': { fileIo },
    '@amber/chat-domain': { MAX_INLINE_TEXT_CHARS: 40000, newId: () => String(++sequence) },
  }, { canIUse: () => true, AppStorage: { get: () => ({ cacheDir: dir }) },
    setTimeout: callback => { assert.equal(activePages, 0, 'yield cannot retain a native page'); calls.yields++; callback(); } });
  const domain = load(path.resolve(__dirname, '../main/ets/domain/input_sources.ts'), {});
  const importer = load(path.join(ENTRY, 'DeepReadSourceImporter.ets'), {
    '@kit.CoreFileKit': { fileIo }, '@kit.ArkTS': { util: {} },
    '@amber/chat-domain': {}, '@amber/deepread-domain': domain,
    './EntryDocumentParser.ets': {}, './PdfDocumentParser.ets': parser,
  });
  return { parser, calls, selected, import: () => importer.importDeepReadSource(selected),
    files: () => fs.readdirSync(dir), cleanup: () => fs.rmSync(dir, { recursive: true, force: true }) };
};

const withPdf = async (pages, check) => {
  const f = fixture(pages);
  try {
    await check(f);
    assert.deepEqual(f.files(), ['selected.pdf'], 'native preview copy must be removed');
    assert.deepEqual(f.calls.releases, f.calls.pages, 'every opened native page must be released');
    assert.ok(f.calls.documentsReleased > 0);
  } finally { f.cleanup(); }
};

test('cleaned long PDF still records extraction truncation and does not save its omitted evidence', async () => {
  await withPdf([['正文内容  \n'.repeat(8000) + '此处为 PDF 尾部关键证据。']], async f => {
    const source = await f.import();
    assert.equal(source.status, 'ready');
    assert.ok(source.content.length < 40000, 'whitespace cleanup shrinks the raw prefix');
    assert.equal(source.content.includes('尾部关键证据'), false);
    assert.equal(source.truncated, true);
    assert.match(source.note, /已截断/);
    assert.doesNotMatch(source.note, /已保存.*40000/);
  });
});

test('complete short PDF has its text and no truncation warning', async () => {
  await withPdf([['首页正文', '另一段'], ['第二页正文']], async f => {
    const source = await f.import();
    assert.equal(source.content, '首页正文\n另一段\n\n第二页正文');
    assert.equal(source.truncated, false); assert.equal(source.note, null);
    assert.equal(f.calls.yields, 1);
  });
});

test('raw text exactly fills the extraction budget without unread text and must not be marked truncated', async () => {
  await withPdf([['正文  \n'.repeat(8000) + '尾']], async f => {
    const source = await f.import();
    assert.ok(source.content.length < 40000);
    assert.equal(source.content.endsWith('尾'), true);
    assert.equal(source.truncated, false); assert.equal(source.note, null);
  });
});

test('an extra nonempty object after an exact raw boundary proves omitted text and ends probing', async () => {
  await withPdf([['正文  \n'.repeat(8000) + '尾', ' \n ', null, '遗漏证据', '不应读取']], async f => {
    const source = await f.import();
    assert.equal(source.truncated, true); assert.match(source.note, /已截断/);
    assert.equal(source.content.includes('遗漏证据'), false);
    assert.deepEqual(f.calls.text, [[0, 0], [0, 1], [0, 3]]);
  });
});

test('boundary probing releases and yields pages, stops at the first later page containing text', async () => {
  await withPdf([['正文  \n'.repeat(8000) + '尾'], [null, ' \n '], ['下一页证据', '不应读取'], ['不应打开']], async f => {
    const source = await f.import();
    assert.equal(source.truncated, true);
    assert.deepEqual(f.calls.pages, [0, 1, 2]);
    assert.deepEqual(f.calls.text, [[0, 0], [1, 1], [2, 0]]);
    assert.equal(f.calls.yields, 2);
  });
});

test('a separator consuming the remaining raw budget still records unread text in that object or page', async () => {
  for (const pages of [[['正文  \n'.repeat(8000), '遗漏正文']], [['正文  \n'.repeat(8000)], ['遗漏正文']]]) {
    await withPdf(pages, async f => {
      const source = await f.import();
      assert.equal(source.truncated, true); assert.match(source.note, /已截断/);
      assert.equal(source.content.includes('遗漏正文'), false);
      assert.ok(source.content.length < 40000);
    });
  }
});

test('an exact boundary followed only by blank/image objects or pages is complete', async () => {
  await withPdf([['正文  \n'.repeat(8000) + '尾', '  '], [null, '\n\n']], async f => {
    const source = await f.import();
    assert.equal(source.truncated, false); assert.equal(source.note, null);
    assert.deepEqual(f.calls.pages, [0, 1]);
  });
});

test('only whitespace omitted within the final object is not lost body text', async () => {
  await withPdf([['正文  \n'.repeat(8000) + '尾   \n']], async f => {
    const source = await f.import();
    assert.equal(source.truncated, false); assert.equal(source.note, null);
    assert.equal(source.content.endsWith('尾'), true);
  });
});

test('normalization still enforces the existing saved body budget for dense PDF text', async () => {
  await withPdf([['字'.repeat(40001)]], async f => {
    const source = await f.import();
    assert.equal(source.content.length, 40000);
    assert.equal(source.truncated, true); assert.match(source.note, /最多保存 40000 字符/);
  });
});

test('existing Chat path/preview APIs return budgeted strings and stop at the budget', async () => {
  await withPdf([['12345'], ['Chat 不需要探查的后页']], async f => {
    assert.equal(await f.parser.parsePdfPath(f.selected, 5), '12345');
    assert.equal(await f.parser.parsePdfPreviewBytes(new TextEncoder().encode('%PDF-fixture'), 5), '12345');
    assert.deepEqual(f.calls.pages, [0, 0]);
  });
});
