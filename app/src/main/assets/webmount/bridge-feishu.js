// Feishu/Lark visible document snapshot. Refs and sequence stay bridge-owned.
// This factory is private to the single assembled bootstrap closure.
function createFeishuSnapshot(rectOf, nodePath, startSnapshot, redactedUrl, cleanText, isElementInViewport) {
  function isFeishuLikeHost(host) {
    host = String(host || '').toLowerCase();
    return host === 'feishu.cn' || host.slice(-10) === '.feishu.cn' ||
      host === 'larksuite.com' || host.slice(-14) === '.larksuite.com' ||
      host === 'larkoffice.com' || host.slice(-15) === '.larkoffice.com' ||
      host === 'feishu.net' || host.slice(-11) === '.feishu.net';
  }

  function feishuDocInfo() {
    var m = location.pathname.match(/\/(docx|docs|doc|wiki|sheets|base|mindnotes)\/([A-Za-z0-9_-]+)/i);
    if (!isFeishuLikeHost(location.hostname) || !m) return null;
    var t = m[1].toLowerCase();
    if (t === 'doc' || t === 'docs') t = 'docx';
    return { doc_type: t, doc_token: m[2] };
  }

  function textFingerprint(text) {
    var s = cleanText(text, 240);
    var h = 0;
    for (var i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    return Math.abs(h).toString(36) + ':' + s.length;
  }

  function feishuBlockType(el, text) {
    var tag = (el.tagName || '').toLowerCase();
    var role = el.getAttribute && el.getAttribute('role');
    if (/^h[1-6]$/.test(tag) || role === 'heading') {
      var level = /^h[1-6]$/.test(tag) ? parseInt(tag.substring(1), 10) : parseInt(el.getAttribute('aria-level') || '2', 10);
      return { type: 'heading', level: level || 2 };
    }
    if (tag === 'pre' || tag === 'code') return { type: 'code', level: null };
    if (/^(\u2022|-|\*)\s+/.test(text)) return { type: 'bullet', level: null };
    if (/^\d+[\.)]\s+/.test(text)) return { type: 'ordered', level: null };
    return { type: 'paragraph', level: null };
  }

  function dataBlockId(el) {
    if (!el || !el.getAttribute) return null;
    return el.getAttribute('data-block-id') ||
      el.getAttribute('data-docx-block-id') ||
      el.getAttribute('data-block-token') ||
      el.getAttribute('data-node-id');
  }

  function collectFeishuBlockElements(maxBlocks) {
    var selectors = [
      '[data-block-id]',
      '[data-docx-block-id]',
      '[data-block-token]',
      '[data-node-id]',
      'article h1, article h2, article h3, article h4, article h5, article h6',
      'article p, article li, article pre, article blockquote',
      '[contenteditable="true"] h1, [contenteditable="true"] h2, [contenteditable="true"] h3',
      '[contenteditable="true"] p, [contenteditable="true"] li, [contenteditable="true"] pre',
      '[role="textbox"] h1, [role="textbox"] h2, [role="textbox"] h3',
      '[role="textbox"] p, [role="textbox"] li, [role="textbox"] pre'
    ].join(',');
    var els = Array.prototype.slice.call(document.querySelectorAll(selectors));
    var out = [];
    var seen = {};
    for (var i = 0; i < els.length && out.length < maxBlocks; i++) {
      var el = els[i];
      if (!isElementInViewport(el)) continue;
      var text = cleanText(el.innerText || el.textContent || '', 2000);
      if (!text || text.length < 2) continue;
      var rect = rectOf(el);
      var key = dataBlockId(el) || (textFingerprint(text) + ':' + Math.round((rect[1] || 0) / 8));
      if (seen[key]) continue;
      seen[key] = true;
      out.push(el);
    }
    return out;
  }

  function fallbackVisibleParagraphs(maxBlocks, snapshotId) {
    var raw = cleanText((document.body && document.body.innerText) || '', 12000);
    if (!raw) return [];
    return raw.split(/\n{2,}/).map(function (p) { return cleanText(p, 1200); })
      .filter(function (p) { return p.length > 1; })
      .slice(0, maxBlocks)
      .map(function (text, idx) {
        return {
          block_ref: 'wfblk_' + snapshotId + '_fallback_' + (idx + 1),
          type: 'paragraph',
          text: text,
          level: null,
          rect: null,
          stable_hint: {
            source: 'visible_text_fallback',
            text_fingerprint: textFingerprint(text),
            scope: 'current_webview_session',
            resolvable: false,
          },
        };
      });
  }

  function feishuSnapshot(args) {
    var info = feishuDocInfo();
    if (!info) {
      return {
        ok: false,
        error: {
          code: 'not_feishu_doc_page',
          message: 'Current WebMount page is not a recognized Feishu/Lark document URL.',
          next_action: 'Open a Feishu cloud document in WebMount, then call feishu_docs_snapshot again.',
        },
      };
    }
    var maxBlocks = Math.min(((args.max_blocks | 0) || 80), 300);
    var selected = '';
    try { selected = cleanText(String(window.getSelection && window.getSelection() || ''), 4000); } catch (_) {}
    var snapshotId = startSnapshot();
    var blocks = [];
    var els = collectFeishuBlockElements(maxBlocks);
    for (var i = 0; i < els.length && blocks.length < maxBlocks; i++) {
      var el = els[i];
      var text = cleanText(el.innerText || el.textContent || '', 2000);
      var kind = feishuBlockType(el, text);
      var blockId = dataBlockId(el);
      var ref = 'wfblk_' + snapshotId + '_' + (blocks.length + 1);
      var css = nodePath(el);
      blocks.push({
        block_ref: ref,
        type: kind.type,
        text: text,
        level: kind.level,
        rect: rectOf(el),
        stable_hint: {
          css: css,
          data_block_id: blockId,
          text_fingerprint: textFingerprint(text),
          scope: 'current_webview_session',
        },
      });
    }
    var limitations = [];
    if (blocks.length === 0) {
      blocks = fallbackVisibleParagraphs(maxBlocks, snapshotId);
      limitations.push('structured_block_dom_not_found');
    }
    if (blocks.length >= maxBlocks) limitations.push('visible_blocks_truncated');
    var outline = blocks.filter(function (b) { return b.type === 'heading'; }).slice(0, 80).map(function (b) {
      return { text: b.text, level: b.level || 2, block_ref: b.block_ref };
    });
    return {
      ok: true,
      mode: 'feishu_docs_snapshot',
      session_scope: 'current_webview_session',
      url: redactedUrl(location.href),
      doc_type: info.doc_type,
      doc_token: info.doc_token,
      title: cleanText(document.title || '', 300),
      selected_text: selected,
      outline: outline,
      visible_blocks: blocks,
      limitations: limitations,
    };
  }

  return feishuSnapshot;
}
