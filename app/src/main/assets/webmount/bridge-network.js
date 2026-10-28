// Page network observation and the existing read-only replay RPC.
// Request numbering and original fetch/XHR methods have one realm-local owner.
function createWebMountNetwork(absoluteUrl, redactedUrl, safeJson) {
  // Monkey-patch XMLHttpRequest and fetch so Kotlin can build redacted request
  // templates and semantic network signals. Android's WebViewClient cannot see
  // POST bodies, so the raw event stays process-local and is sanitized before
  // any tool output reaches the model. Best effort: page bundle JS may fire
  // requests before fallback re-injection; the Kotlin side reports coverage.
  if (!window.__amberWmNetShimInstalled) {
    window.__amberWmNetShimInstalled = true;
    window.__amberWmNetPending = window.__amberWmNetPending || 0;
    window.__amberWmNetSignalSeq = window.__amberWmNetSignalSeq || 0;
    var netSeq = { n: 0 };

    function bodyToString(body) {
      if (body == null) return null;
      try {
        if (typeof body === 'string') return body.length > 256000 ? (body.substring(0, 256000) + '…[truncated]') : body;
        if (body instanceof FormData) {
          var entries = [];
          // body.entries() may be unavailable in some old WebViews — guard.
          if (typeof body.entries === 'function') {
            try {
              var it = body.entries();
              while (true) {
                var step = it.next();
                if (step.done) break;
                entries.push([String(step.value[0]), step.value[1] instanceof Blob ? ('<blob ' + step.value[1].size + ' bytes>') : String(step.value[1])]);
                if (entries.length >= 100) break;
              }
            } catch (_) { /* ignore */ }
          }
          return JSON.stringify({ __form: entries });
        }
        if (body instanceof Blob) return '<blob ' + body.size + ' bytes>';
        if (body instanceof ArrayBuffer) return '<arraybuffer ' + body.byteLength + ' bytes>';
        if (body.toString) return body.toString();
      } catch (_) { /* ignore */ }
      return '<unserializable>';
    }

    function trimResponse(t) {
      if (t == null) return null;
      return t.length > 65536 ? (t.substring(0, 65536) + '…[truncated]') : t;
    }

    function isSignalRequest(url) {
      var u = String(url || '').toLowerCase();
      var path = u.split(/[?#]/, 1)[0];
      return !(
        u.indexOf('google-analytics') >= 0 ||
        u.indexOf('/analytics') >= 0 ||
        u.indexOf('/beacon') >= 0 ||
        u.indexOf('doubleclick') >= 0 ||
        u.indexOf('googletagmanager') >= 0 ||
        u.indexOf('sentry') >= 0 ||
        u.indexOf('log.') >= 0 ||
        /(?:^|\/)logs?(?:\/|$)/.test(path) ||
        u.indexOf('/metrics') >= 0 ||
        u.indexOf('/collect') >= 0
      );
    }

    function sendNet(evt) {
      try {
        if (evt && evt.url && isSignalRequest(evt.url)) {
          window.__amberWmNetSignalSeq = (window.__amberWmNetSignalSeq || 0) + 1;
        }
        AmberWM.onNetworkEvent(JSON.stringify(evt));
      } catch (e) { /* bridge gone — ignore */ }
    }

    // ---- XHR ----
    var XHR_OPEN = XMLHttpRequest.prototype.open;
    var XHR_SEND = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__amberWm = {
        id: ++netSeq.n,
        method: String(method).toUpperCase(),
        url: absoluteUrl(url),
        ts: Date.now(),
      };
      return XHR_OPEN.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      var meta = this.__amberWm;
      if (meta) {
        meta.body_preview = bodyToString(body);
        window.__amberWmNetPending = (window.__amberWmNetPending || 0) + 1;
        sendNet({
          type: 'xhr_send', req_id: meta.id, method: meta.method, url: meta.url,
          ts: meta.ts, body_preview: meta.body_preview,
        });
        var self = this;
        this.addEventListener('loadend', function () {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'xhr_done', req_id: meta.id, method: meta.method, url: meta.url,
            status: self.status, response_preview: trimResponse(self.responseText),
            response_chars: self.responseText ? self.responseText.length : 0,
            ts: Date.now(),
          });
        });
      }
      try {
        return XHR_SEND.apply(this, arguments);
      } catch (err) {
        if (meta) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'xhr_error', req_id: meta.id, method: meta.method, url: meta.url,
            error: String(err && err.message || err), ts: Date.now(),
          });
        }
        throw err;
      }
    };

    // ---- fetch ----
    var ORIG_FETCH = window.fetch;
    if (typeof ORIG_FETCH === 'function') {
      window.fetch = function (input, init) {
        var reqId = ++netSeq.n;
        var url = absoluteUrl((typeof input === 'string') ? input : (input && input.url) || '');
        var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        var body = init && init.body ? bodyToString(init.body) : null;
        window.__amberWmNetPending = (window.__amberWmNetPending || 0) + 1;
        sendNet({
          type: 'fetch_send', req_id: reqId, method: method, url: String(url),
          body_preview: body, ts: Date.now(),
        });
        var fetchPromise;
        try {
          fetchPromise = ORIG_FETCH.apply(this, arguments);
        } catch (err) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_error', req_id: reqId, method: method, url: String(url),
            error: String(err && err.message || err), ts: Date.now(),
          });
          throw err;
        }
        return fetchPromise.then(function (resp) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_done', req_id: reqId, method: method, url: String(url),
            status: resp.status, response_preview: null, response_chars: 0, ts: Date.now(),
          });
          // Tee the response so the page still gets it.
          var clone;
          try { clone = resp.clone(); } catch (_) { clone = null; }
          if (clone) {
            clone.text().then(function (t) {
              sendNet({
                type: 'fetch_preview', req_id: reqId, method: method, url: String(url),
                status: resp.status, response_preview: trimResponse(t),
                response_chars: t ? t.length : 0, ts: Date.now(),
              });
            }).catch(function () { /* body already consumed; ignore */ });
          }
          return resp;
        }, function (err) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_error', req_id: reqId, method: method, url: String(url),
            error: String(err && err.message || err), ts: Date.now(),
          });
          throw err;
        });
      };
    }
  }

  function performFetchReplay(args, reqId) {
    var method = String(args.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
      AmberWM.reject(reqId, 'fetch_replay only supports GET/HEAD');
      return;
    }
    var url = String(args.url || '');
    if (!url) {
      AmberWM.reject(reqId, 'fetch_replay requires url');
      return;
    }
    var maxChars = Math.max(0, Math.min((args.max_chars | 0) || 60000, 200000));
    fetch(url, { method: method, credentials: 'include', cache: 'no-store' }).then(function (resp) {
      var headers = {};
      try {
        resp.headers.forEach(function (value, key) {
          var lower = String(key).toLowerCase();
          if (lower === 'set-cookie' || lower === 'cookie' || lower === 'authorization') return;
          if (lower.indexOf('token') >= 0 || lower.indexOf('secret') >= 0) return;
          headers[key] = value;
        });
      } catch (_) {}
      return resp.text().then(function (text) {
        AmberWM.resolve(reqId, safeJson({
          ok: true,
          url: redactedUrl(resp.url || url),
          status: resp.status,
          content_type: resp.headers.get('content-type') || '',
          headers: headers,
          text: text.substring(0, maxChars),
          text_chars: text.length,
          truncated: text.length > maxChars,
        }));
      }, function (err) {
        AmberWM.resolve(reqId, safeJson({
          ok: false,
          error: String(err && err.message || err),
        }));
      });
    }, function (err) {
      AmberWM.resolve(reqId, safeJson({
        ok: false,
        error: String(err && err.message || err),
      }));
    });
  }

  return performFetchReplay;
}
