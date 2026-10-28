package app.amber.feature.ui.components.message

internal const val WIDGET_MIN_PARTIAL_RENDER_CHARS = 16

internal fun String.toStableStreamingWidgetHtml(): String? {
    var stable = trim()
    if (stable.length < WIDGET_MIN_PARTIAL_RENDER_CHARS) return null

    val scriptStart = stable.lastIndexOf("<script", ignoreCase = true)
    if (scriptStart >= 0 && stable.indexOf("</script", startIndex = scriptStart, ignoreCase = true) < 0) {
        stable = stable.substring(0, scriptStart).trim()
    }

    val styleStart = stable.lastIndexOf("<style", ignoreCase = true)
    if (styleStart >= 0 && stable.indexOf("</style", startIndex = styleStart, ignoreCase = true) < 0) {
        stable = stable.substring(0, styleStart).trim()
    }

    val lastOpenTag = stable.lastIndexOf('<')
    val lastCloseTag = stable.lastIndexOf('>')
    if (lastOpenTag > lastCloseTag) {
        stable = stable.substring(0, lastOpenTag).trim()
    }

    return stable
        .closeStreamingSvgIfNeeded()
        .stripStreamingAnimations()
        .takeIf { it.length >= WIDGET_MIN_PARTIAL_RENDER_CHARS }
}

// Streaming pushes replace root.innerHTML on every debounce; a declarative
// animation in the partial markup would restart from t=0 each push and flicker.
// The preview drops SMIL nodes and mutes CSS animation so the scene draws
// statically while streaming; the finalize push re-inserts the real markup and
// animation starts exactly once.
private val streamingSmilBlock = Regex(
    """<\s*(animate|animateColor|animateTransform|animateMotion|set)\b[\s\S]*?<\s*/\s*\1\s*>""",
    RegexOption.IGNORE_CASE,
)
private val streamingSmilVoid = Regex(
    """<\s*(animate|animateColor|animateTransform|animateMotion|set)\b[^>]*?/\s*>""",
    RegexOption.IGNORE_CASE,
)
private val streamingSmilLoneTag = Regex(
    """<\s*/?\s*(animate|animateColor|animateTransform|animateMotion|set)\b[^>]*>""",
    RegexOption.IGNORE_CASE,
)
// #root prefix beats single-class/id selectors, so a widget-authored
// !important animation can't outrank the streaming mute.
private const val STREAMING_ANIMATION_MUTE =
    "<style>#root *,#root *::before,#root *::after{animation:none!important;transition:none!important}</style>"

internal fun String.stripStreamingAnimations(): String {
    if (isBlank()) return this
    if (!contains("<animate", ignoreCase = true) && !contains("<set", ignoreCase = true)) {
        return this + STREAMING_ANIMATION_MUTE
    }
    // Void forms first: a self-closed <animate/> consumed by the block regex
    // would eat everything up to the next unrelated close tag.
    return replace(streamingSmilVoid, "")
        .replace(streamingSmilBlock, "")
        .replace(streamingSmilLoneTag, "")
        .plus(STREAMING_ANIMATION_MUTE)
}

private fun String.closeStreamingSvgIfNeeded(): String {
    val compact = trim()
    if (!compact.startsWith("<svg", ignoreCase = true) || compact.contains("</svg", ignoreCase = true)) {
        return compact
    }
    return "$compact</svg>"
}

internal fun String.toStableWidgetKeyFragment(): String {
    val lines = trim()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .take(6)
        .joinToString("")
        .take(220)
    return lines.ifBlank { "hash-${hashCode()}" }
}

internal fun buildReceiverHtml(
    bridgeToken: String,
    background: String,
    foreground: String,
    surface: String,
    outline: String,
    primary: String,
    interactive: Boolean,
    fillContainer: Boolean = false,
): String = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1${if (interactive) ",minimum-scale=0.5,maximum-scale=5,user-scalable=yes" else ""}">
<script>
// Defined eagerly in <head> so any host evaluateJavascript() that fires before the body
// script runs gets queued instead of crashing with "is not a function".
window.__amberWidgetPending = null;
window.__amberWidgetReady = false;
window.__amberWidgetPaused = false;
window.__amberWidgetSetHtml = function(html){
  if (window.__amberWidgetReady && window.__amberWidgetSetHtmlReal) {
    window.__amberWidgetSetHtmlReal(html);
  } else {
    window.__amberWidgetPending = { html: html, finalize: false };
  }
};
window.__amberWidgetFinalizeHtml = function(html){
  if (window.__amberWidgetReady && window.__amberWidgetFinalizeHtmlReal) {
    window.__amberWidgetFinalizeHtmlReal(html);
  } else {
    window.__amberWidgetPending = { html: html, finalize: true };
  }
};
</script>
<style>
html,body{margin:0;padding:0;background:$background;color:$foreground;font-family:system-ui,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;${if (fillContainer) "height:100%;" else ""}}
*{box-sizing:border-box;max-width:100%;}
body{overflow:${if (interactive) "auto" else "hidden"};${if (fillContainer) "-webkit-overflow-scrolling:touch;" else ""}}
#root{width:100%;${if (fillContainer) "min-height:100%;" else "min-height:1px;"}overflow:${if (interactive) "auto" else "hidden"};}
#root>*{max-width:100%!important;}
svg{display:block;width:100%!important;max-width:100%;height:auto;overflow:hidden;}
table{width:100%;border-collapse:collapse;}
td,th{border:1px solid $outline;padding:6px 8px;}
button,input,select{font:inherit;border:1px solid $outline;border-radius:8px;background:$surface;color:$foreground;padding:6px 10px;}
a{color:$primary;text-decoration:none;}
</style>
</head>
<body>
<div id="root"></div>
<script>
(function(){
  var root=document.getElementById('root');
  var reportFrame=null;
  // Content-aware height: streaming widget skeletons report huge
  // scrollHeight before any real content arrives (empty table rows already
  // carry the receiver CSS border+padding, placeholder divs carry template
  // min-heights) — inflating the card to maxWidgetHeightDp of blank canvas.
  // Only count elements that ARE visible content: non-whitespace text,
  // graphics, or a painted background. Skeleton extent below the last real
  // content is dropped; it re-grows as content streams in.
  function hasDirectText(el){
    for (var i = 0; i < el.childNodes.length; i++) {
      var n = el.childNodes[i];
      if (n.nodeType === 3 && n.textContent.trim()) return true;
    }
    return false;
  }
  function isContentElement(el, cs){
    // Inline <svg> in an HTML document reports a lowercase tagName.
    var tag = el.tagName.toUpperCase();
    if (tag === 'IMG' || tag === 'SVG' || tag === 'CANVAS' ||
        tag === 'VIDEO' || tag === 'IFRAME' || tag === 'HR') return true;
    if (tag === 'TD' || tag === 'TH') {
      // Empty cells are pure skeleton — borders alone must not size the card.
      return hasDirectText(el) || el.querySelector('img,svg,canvas,video') !== null;
    }
    if (hasDirectText(el)) return true;
    if (cs.backgroundColor && cs.backgroundColor !== 'rgba(0, 0, 0, 0)' && cs.backgroundColor !== 'transparent') {
      return true;
    }
    return false;
  }
  function contentExtentBottom(){
    var rootRect = root.getBoundingClientRect();
    var maxBottom = 0;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
    var el;
    while ((el = walker.nextNode())) {
      var cs = window.getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;
      if (!isContentElement(el, cs)) continue;
      var r = el.getBoundingClientRect();
      if (r.width <= 0 || r.height <= 0) continue;
      maxBottom = Math.max(maxBottom, r.bottom);
    }
    return Math.max(Math.ceil(maxBottom - rootRect.top), 1);
  }
  window.__amberWidgetApplyPause = function(){
    try{
      document.querySelectorAll('svg').forEach(function(s){if(s.pauseAnimations)s.pauseAnimations()});
      document.getAnimations().forEach(function(a){a.pause()});
    }catch(e){}
  };
  window.__amberWidgetApplyResume = function(){
    try{
      document.querySelectorAll('svg').forEach(function(s){if(s.unpauseAnimations)s.unpauseAnimations()});
      document.getAnimations().forEach(function(a){a.play()});
    }catch(e){}
  };
  function report(){
    if(reportFrame !== null) return;
    reportFrame=requestAnimationFrame(function(){
      reportFrame=null;
      AmberWidget.resize(contentExtentBottom());
    });
  }
  function clampSvgOverflow(){
    // 1) Force every SVG to clip to its viewBox.
    // 2) Force SVG to have a real, responsive height. Without an explicit height attribute,
    //    Chromium's CSS "height: auto" on SVG falls back to the replaced-element default of
    //    150px — which compresses the entire viewBox into a tiny stripe. The inline card
    //    (height-by-content) ends up only 150px tall while the mid-card (fillMaxSize) accidentally
    //    looks correct because the parent already enforced a height. Computing aspect-ratio from
    //    viewBox makes the SVG height responsive in BOTH layouts.
    var svgs = root.getElementsByTagName('svg');
    for (var i = 0; i < svgs.length; i++) {
      var s = svgs[i];
      s.setAttribute('overflow', 'hidden');
      s.style.overflow = 'hidden';
      var vb = s.getAttribute('viewBox');
      if (vb) {
        var parts = vb.split(/[\s,]+/);
        if (parts.length === 4) {
          var w = parseFloat(parts[2]);
          var h = parseFloat(parts[3]);
          if (w > 0 && h > 0) {
            s.style.aspectRatio = w + ' / ' + h;
            if (!s.getAttribute('width')) s.style.width = '100%';
            s.style.height = 'auto';
          }
        }
      }
    }
  }
  window.__amberWidgetSetHtmlReal = function(html){
    // Compare against the last PUSHED string, not root.innerHTML — serializing
    // the DOM never round-trips byte-equal, so every recomposition would
    // replace innerHTML and restart declarative animation timelines.
    if(window.__amberWidgetLastHtml!==html){
      if(window.__amberGenerationCancel) window.__amberGenerationCancel();
      window.__amberWidgetLastHtml=html;
      root.innerHTML=html;
      // A fresh <svg> starts its SMIL timeline unpaused — re-apply the host's
      // pause state so a push landing while backgrounded stays frozen.
      if(window.__amberWidgetPaused) window.__amberWidgetApplyPause();
      clampSvgOverflow();
    }
    report();
  };
  window.__amberWidgetFinalizeHtmlReal = function(html){
    if(window.__amberWidgetLastHtml!==html){
      if(window.__amberGenerationCancel) window.__amberGenerationCancel();
      window.__amberWidgetLastHtml=html;
      root.innerHTML=html;
      if(window.__amberWidgetPaused) window.__amberWidgetApplyPause();
      clampSvgOverflow();
    }
    setTimeout(report, 32);
  };
  // Drain any push that landed before this script ran.
  window.__amberWidgetReady = true;
  if (window.__amberWidgetPending) {
    var p = window.__amberWidgetPending;
    window.__amberWidgetPending = null;
    if (p.finalize) window.__amberWidgetFinalizeHtmlReal(p.html);
    else window.__amberWidgetSetHtmlReal(p.html);
  }
  new ResizeObserver(report).observe(root);
  document.addEventListener('click', function(event){
    if(!event.isTrusted) return;
    var target=event.target;
    var anchor=target && target.closest ? target.closest('a[href]') : null;
    if(anchor){
      var href=anchor.getAttribute('href') || '';
      if(href.indexOf('http://')===0 || href.indexOf('https://')===0){
        event.preventDefault();
        AmberWidget.openUrl('$bridgeToken', href);
      }
      return;
    }
    if(event.defaultPrevented) return;
    ${if (!interactive) """try{
      event.preventDefault();
      AmberWidget.tap('$bridgeToken');
    }catch(e){
    }""" else ""}
  });
  report();
})();
</script>
</body>
</html>
""".trimIndent()
