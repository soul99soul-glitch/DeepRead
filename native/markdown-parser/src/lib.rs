//! markdown-parser: Rust replacement for `app/.../richtext/Markdown.kt`'s
//! `parser.buildMarkdownTreeFromString(text)` call (JetBrains markdown lib).
//!
//! Strategy:
//!   - pulldown-cmark events → tree IR → packed binary
//!   - JNI returns ByteArray; Kotlin side has `PackedAstNode` lazy view that
//!     mimics the org.intellij.markdown.ast.ASTNode interface
//!
//! Also exposes `parseMarkdownToHtmlNative` (Component #8) — direct
//! pulldown-cmark events → HTML emit, replacing MarkdownNew.kt's
//! HtmlGenerator + Jsoup-reparse two-pass pipeline.
//!
//! See `docs/RUST_NATIVE_SPIKE_PLAN.md` §4 for full design.

mod packed_ast;
mod tree_builder;
mod type_mapping;

// Pure (non-JNI) entry points for the dump-corpus bin and host-side tools.
pub use packed_ast::pack;
pub use tree_builder::build_tree;

use std::panic::{catch_unwind, AssertUnwindSafe};
use pulldown_cmark::{Event, Options, Parser, Tag, TagEnd};

/// Single source of truth for which pulldown-cmark extensions we enable.
/// Both the packed-AST path (`tree_builder::build_tree`) and the HTML emit
/// path (`render_to_html`) call this so they can't drift.
///
/// Choices:
/// - `ENABLE_TABLES` / `ENABLE_FOOTNOTES` / `ENABLE_STRIKETHROUGH` /
///   `ENABLE_TASKLISTS` — GFM extensions present in JetBrains GFMFlavour.
/// - `ENABLE_HEADING_ATTRIBUTES` — supports `{#id}` heading anchors.
/// - `ENABLE_MATH` — `$...$` / `$$...$$` math blocks.
/// - `ENABLE_GFM` — GFM autolink for bare URLs + extension flags.
/// - **NOT** `ENABLE_SMART_PUNCTUATION` — JetBrains doesn't do smart-quote
///   conversion, so leaving it on would diff `'hello'` vs `'hello'` on
///   every comparison run (Round 2 review P1 fix).
pub(crate) fn markdown_options() -> Options {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_FOOTNOTES);
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TASKLISTS);
    opts.insert(Options::ENABLE_HEADING_ATTRIBUTES);
    opts.insert(Options::ENABLE_MATH);
    opts.insert(Options::ENABLE_GFM);
    opts
}

use jni::objects::{JClass, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;

/// JNI entry: `MarkdownParserNative.parseMarkdownNative(text: String): ByteArray`.
///
/// Returns a packed binary AST blob. On error returns an empty byte array
/// and the Kotlin adapter falls back to the JVM JetBrains-markdown parser.
///
/// # Safety
/// Standard JNI signature. Panics are caught and converted to empty returns.
#[no_mangle]
pub extern "system" fn Java_app_amber_agent_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jbyteArray {
    jni_common::init_logger_once!("RustMarkdownParser");

    let text_str: String = match env.get_string(&text) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("markdown-parser: failed to get JString: {}", e);
            return empty_array(&mut env);
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| parse_to_packed(&text_str)));
    match result {
        Ok(blob) => match env.byte_array_from_slice(&blob) {
            Ok(arr) => arr.into_raw(),
            Err(e) => {
                log::error!("markdown-parser: byte_array_from_slice failed: {}", e);
                empty_array(&mut env)
            }
        },
        Err(panic) => {
            log::error!("markdown-parser: native panic: {:?}", jni_common::panic_to_string(&panic));
            empty_array(&mut env)
        }
    }
}

fn empty_array<'local>(env: &mut JNIEnv<'local>) -> jbyteArray {
    match env.new_byte_array(0) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn parse_to_packed(text: &str) -> Vec<u8> {
    let tree = tree_builder::build_tree(text);
    packed_ast::pack(&tree)
}

/// JNI entry for Component #8: `MarkdownParserNative.parseMarkdownToHtmlNative(text): String?`.
///
/// Single-pass markdown → HTML conversion via `pulldown_cmark::html::push_html`,
/// **intended** to replace the JVM `MarkdownNew.kt` HtmlGenerator hop. The
/// production caller has not yet been switched over; this is spike infrastructure.
///
/// Returns `null` on JString conversion failure / panic — Kotlin adapter then
/// falls back to the JVM HtmlGenerator path.
#[no_mangle]
pub extern "system" fn Java_app_amber_agent_ui_components_richtext_nativebridge_MarkdownParserNative_parseMarkdownToHtmlNative<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> jni::sys::jstring {
    jni_common::init_logger_once!("RustMarkdownParser");

    let text_str: String = match env.get_string(&text) {
        Ok(s) => String::from(s),
        Err(e) => {
            log::error!("markdown-parser (to-html): failed to get JString: {}", e);
            return std::ptr::null_mut();
        }
    };

    let result = catch_unwind(AssertUnwindSafe(|| render_to_html(&text_str)));
    match result {
        Ok(html) => match env.new_string(html) {
            Ok(jstr) => jstr.into_raw(),
            Err(e) => {
                log::error!("markdown-parser (to-html): new_string failed: {}", e);
                std::ptr::null_mut()
            }
        },
        Err(panic) => {
            log::error!(
                "markdown-parser (to-html): native panic: {:?}",
                jni_common::panic_to_string(&panic)
            );
            std::ptr::null_mut()
        }
    }
}

/// Pure-Rust markdown → HTML emit. Uses the SAME `Options` set as
/// `tree_builder::make_options` so the packed-AST path and the HTML path
/// agree on which GFM extensions are enabled.
///
/// Two safety nets mirror JetBrains' GFMFlavourDescriptor flags:
/// - Bare HTTP/HTTPS URLs wrapped in `<...>` before parsing so pulldown-cmark
///   emits anchors (mirrors `makeHttpsAutoLinks = true`; Round 1 P1 fix).
/// - Link/image destinations and raw HTML attributes strip dangerous URL
///   schemes to mirror `useSafeLinks = true`, without changing text or code.
fn render_to_html(text: &str) -> String {
    use pulldown_cmark::html;
    let pre = autolink_bare_urls(text);
    // Raw HTML arrives as line events, while a tag can span several lines.
    // Sanitize the complete parser-owned block, leaving Text/Code untouched.
    let mut raw_html_block = String::new();
    let parser = Parser::new_ext(&pre, markdown_options()).filter_map(|event| match event {
        Event::Start(Tag::HtmlBlock) => {
            raw_html_block.clear();
            None
        }
        Event::Html(html) => {
            raw_html_block.push_str(&html);
            None
        }
        Event::End(TagEnd::HtmlBlock) => Some(Event::Html(
            sanitize_raw_html_attributes(&raw_html_block).into(),
        )),
        _ => Some(sanitize_link_event(event)),
    });
    let mut html_out = String::with_capacity(pre.len() + (pre.len() / 4));
    html::push_html(&mut html_out, parser);
    html_out
}

/// Wrap bare `https?://...` URLs in CommonMark autolink `<...>` syntax so
/// pulldown-cmark renders them as anchors. Skip URLs that are already
/// in `<...>` (autolink) or `[text](url)` (markdown link) or in code spans.
///
/// UTF-8 safe: copies untouched spans via string slicing rather than
/// byte-by-byte char cast (the byte-cast approach corrupts every multi-byte
/// code point — same regression we fixed in EpubParser::collapse_newlines).
fn autolink_bare_urls(text: &str) -> String {
    use std::sync::OnceLock;
    static URL_RE: OnceLock<regex::Regex> = OnceLock::new();
    let re = URL_RE.get_or_init(|| {
        regex::Regex::new(r#"https?://[^\s<>\)\]\}]+"#).expect("autolink regex compiles")
    });

    // Find spans to leave alone (already-linked / code blocks). We collect
    // byte ranges that should NOT be matched against the URL regex; anything
    // outside these ranges may have its URLs wrapped.
    let skip_ranges = collect_skip_ranges(text);

    let mut out = String::with_capacity(text.len() + 16);
    let mut cursor = 0usize;
    for mat in re.find_iter(text) {
        if mat.start() < cursor {
            // Overlapping previous match (shouldn't happen with non-overlapping iter)
            continue;
        }
        if in_any_range(&skip_ranges, mat.start()) {
            // Inside a skip span — leave URL alone. Copy through unchanged.
            continue;
        }
        // Copy text between cursor and match start verbatim (slice is UTF-8 safe)
        out.push_str(&text[cursor..mat.start()]);
        let raw = mat.as_str();
        let trimmed = raw.trim_end_matches(|c: char| {
            matches!(c, '.' | ',' | ';' | ':' | '!' | '?')
        });
        out.push('<');
        out.push_str(trimmed);
        out.push('>');
        cursor = mat.start() + trimmed.len();
        // Don't push back stripped punctuation here — it's part of the original
        // text past `cursor` and the next iteration / final copy will emit it.
    }
    out.push_str(&text[cursor..]);
    out
}

/// Use the parser's own source ranges so all valid code delimiters and links
/// are protected, including indented code and fences that continue to EOF.
fn collect_skip_ranges(text: &str) -> Vec<(usize, usize)> {
    Parser::new_ext(text, markdown_options())
        .into_offset_iter()
        .filter_map(|(event, range)| match event {
            Event::Start(Tag::CodeBlock(_) | Tag::Link { .. } | Tag::Image { .. })
            | Event::Code(_)
            | Event::Html(_)
            | Event::InlineHtml(_) => Some((range.start, range.end)),
            _ => None,
        })
        .collect()
}

fn in_any_range(ranges: &[(usize, usize)], pos: usize) -> bool {
    ranges.iter().any(|&(start, end)| pos >= start && pos < end)
}

fn sanitize_link_event(mut event: Event<'_>) -> Event<'_> {
    match &mut event {
        Event::Start(Tag::Link { dest_url, .. } | Tag::Image { dest_url, .. }) => {
            if has_dangerous_scheme(dest_url) {
                *dest_url = "#".into();
            }
        }
        Event::InlineHtml(html) => {
            *html = sanitize_raw_html_attributes(html).into();
        }
        _ => {}
    }
    event
}

fn has_dangerous_scheme(url: &str) -> bool {
    url.split_once(':').is_some_and(|(scheme, _)| {
        ["javascript", "data", "vbscript", "file"]
            .iter()
            .any(|dangerous| scheme.eq_ignore_ascii_case(dangerous))
    })
}

/// Raw HTML events can also contain character data. Only quoted href/src
/// attributes in actual tags participate in the existing safe-link policy.
fn sanitize_raw_html_attributes(html: &str) -> String {
    use std::sync::OnceLock;
    static TAG_RE: OnceLock<regex::Regex> = OnceLock::new();
    static ATTRIBUTE_RE: OnceLock<regex::Regex> = OnceLock::new();
    let tags = TAG_RE.get_or_init(|| {
        regex::Regex::new(r#"<[A-Za-z](?:[^"'<>]|"[^"]*"|'[^']*')*>"#)
            .expect("HTML tag regex compiles")
    });
    let attributes = ATTRIBUTE_RE.get_or_init(|| {
        regex::Regex::new(r#"([^\s"'<>/=]+)(\s*=\s*)("[^"]*"|'[^']*')"#)
            .expect("HTML attribute regex compiles")
    });
    tags.replace_all(html, |tag: &regex::Captures| {
        attributes
            .replace_all(&tag[0], |attribute: &regex::Captures| {
                let name = &attribute[1];
                let value = &attribute[3];
                if (name.eq_ignore_ascii_case("href") || name.eq_ignore_ascii_case("src"))
                    && has_dangerous_scheme(&value[1..value.len() - 1])
                {
                    let quote = &value[..1];
                    format!("{name}{}{quote}#{quote}", &attribute[2])
                } else {
                    attribute[0].to_owned()
                }
            })
            .into_owned()
    })
    .into_owned()
}


#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sanity_empty_input() {
        let out = parse_to_packed("");
        // Header still emitted even for empty input
        assert!(out.len() >= 8, "header must be present");
        assert_eq!(&out[..4], b"PMDA");
    }

    #[test]
    fn parses_paragraph() {
        let out = parse_to_packed("hello world");
        assert!(out.len() > 8);
        assert_eq!(&out[..4], b"PMDA");
    }

    #[test]
    fn html_renders_paragraph() {
        let html = render_to_html("hello");
        assert!(html.contains("<p>hello</p>"), "got: {}", html);
    }

    #[test]
    fn html_renders_empty_input() {
        let html = render_to_html("");
        assert_eq!(html, "");
    }

    /// JetBrains' makeHttpsAutoLinks converts bare URLs to anchors. With
    /// `ENABLE_GFM` we get the same behaviour from pulldown-cmark.
    /// Round 1 P1 fix verification.
    #[test]
    fn html_autolinks_bare_url() {
        let html = render_to_html("visit https://example.com today");
        // Either `<a href="https://example.com">https://example.com</a>` or
        // a similar GFM autolink wrapping. We assert the URL becomes an anchor.
        assert!(
            html.contains("<a href=\"https://example.com"),
            "expected autolinked URL, got: {}",
            html
        );
    }

    #[test]
    fn html_preserves_bare_url_in_tilde_fence() {
        let html = render_to_html(
            "<div>raw block</div>\n\n~~~text\nhttps://example.com/path\n~~~\n",
        );
        assert!(
            html.contains("<code class=\"language-text\">https://example.com/path\n</code>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_preserves_bare_url_in_indented_code() {
        let html = render_to_html("<div>raw block</div>\n\n    https://example.com/path\n");
        assert!(
            html.contains("<pre><code>https://example.com/path\n</code></pre>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_preserves_bare_url_in_unclosed_fence() {
        let html = render_to_html("<div>raw block</div>\n\n```text\nhttps://example.com/path\n");
        assert!(
            html.contains("<code class=\"language-text\">https://example.com/path\n</code>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_preserves_bare_url_in_code_span_with_inner_backtick() {
        let html = render_to_html(
            "<div>raw block</div>\n\n``code ` https://example.com/path``",
        );
        assert!(
            html.contains("<code>code ` https://example.com/path</code>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_preserves_dangerous_scheme_literal_in_code() {
        let html = render_to_html(
            "<div>raw block</div>\n\n```text\nhref=\"javascript:alert(1)\"\n```\n",
        );
        assert!(
            html.contains("<code class=\"language-text\">href=\"javascript:alert(1)\"\n</code>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_preserves_dangerous_scheme_literal_in_text() {
        let html = render_to_html("<div>raw block</div>\n\nhref=\"javascript:alert(1)\"");
        assert!(
            html.contains("<p>href=\"javascript:alert(1)\"</p>"),
            "got: {html}"
        );
    }

    #[test]
    fn html_sanitizes_markdown_link_and_image_destinations() {
        let html = render_to_html("[click](javascript:alert(1))\n\n![image](javascript:alert(1))");
        assert!(html.contains("<a href=\"#\">click</a>"), "got: {html}");
        assert!(html.contains("<img src=\"#\""), "got: {html}");
        assert!(!html.contains("javascript:"), "got: {html}");
    }

    #[test]
    fn html_sanitizes_raw_html_href_and_src() {
        let html = render_to_html(
            "<div><a href=\"javascript:alert(1)\">click</a><img src=\"javascript:alert(1)\"></div>",
        );
        assert!(html.contains("<a href=\"#\">click</a>"), "got: {html}");
        assert!(html.contains("<img src=\"#\">"), "got: {html}");
        assert!(!html.contains("javascript:"), "got: {html}");
    }

    #[test]
    fn html_preserves_raw_html_text_and_non_url_attributes() {
        let html = render_to_html(
            "<div title='href=\"javascript:literal\"' data-href=\"javascript:opaque\">href=\"javascript:sample\"<a href='javascript:alert(1)'>click</a></div>",
        );
        let expected = r#"<div title='href="javascript:literal"' data-href="javascript:opaque">href="javascript:sample"<a href='#'>click</a></div>"#;
        assert!(html.contains(expected), "got: {html}");
    }

    #[test]
    fn html_sanitizes_multiline_raw_html_tags() {
        let html = render_to_html(
            "<div>\n<a\n href=\"javascript:alert(1)\">click</a>\n<img\n src='file:/tmp/image'>\n</div>",
        );
        assert!(html.contains("href=\"#\""), "got: {html}");
        assert!(html.contains("src='#'"), "got: {html}");
        assert!(!html.contains("javascript:") && !html.contains("file:"), "got: {html}");
    }

    /// useSafeLinks=true in JetBrains strips dangerous schemes. Round 1 P1 fix.
    #[test]
    fn html_strips_javascript_href() {
        let html = render_to_html("[click](javascript:alert(1))");
        assert!(
            !html.contains("javascript:"),
            "javascript: scheme not stripped: {}",
            html
        );
        assert!(html.contains("href=\"#\""), "expected sanitized href: {}", html);
    }

    #[test]
    fn html_strips_data_uri_href() {
        let html = render_to_html("[click](data:text/html,<script>)");
        assert!(!html.contains("data:"), "data: scheme not stripped: {}", html);
    }

    #[test]
    fn html_passes_through_safe_https() {
        let html = render_to_html("[ok](https://example.com)");
        assert!(html.contains("href=\"https://example.com\""), "got: {}", html);
    }

    #[test]
    fn html_preserves_literals_inside_raw_html_block() {
        let html = render_to_html("<div>href=\"javascript:example\" <a title=\"a > b\" href=\"javascript:alert(1)\">link</a></div>");
        assert!(html.contains("<div>href=\"javascript:example\""), "got: {html}");
        assert!(html.contains("title=\"a > b\" href=\"#\""), "got: {html}");
    }

    #[test]
    fn html_preserves_existing_link_and_raw_html_urls() {
        let html = render_to_html(
            "[label](https://example.com/path)\n\n<a href=\"https://example.com/raw\">raw</a>",
        );
        assert!(html.contains("<a href=\"https://example.com/path\">label</a>"), "got: {html}");
        assert!(html.contains("<a href=\"https://example.com/raw\">raw</a>"), "got: {html}");
    }

    #[test]
    fn html_sanitizes_mixed_case_destinations_without_touching_code() {
        let html = render_to_html(
            "[click](JaVaScRiPt:alert(1)) `href=\"javascript:alert(1)\"`",
        );
        assert!(html.contains("<a href=\"#\">click</a>"), "got: {html}");
        assert!(html.contains("<code>href=\"javascript:alert(1)\"</code>"), "got: {html}");
    }

    #[test]
    fn html_preserves_cjk() {
        let html = render_to_html("你好世界");
        assert!(html.contains("你好世界"), "got: {}", html);
    }
}
