// ZCode v4 read/prepare/send protocol. Its single-use ticket and sequence
// live together; snapshot/ref validation and native actions stay bridge-owned.
function createZCodeProtocol(helpers) {
  var roleOf = helpers.roleOf;
  var rectOf = helpers.rectOf;
  var isVisible = helpers.isVisible;
  var nodePath = helpers.nodePath;
  var describeNode = helpers.describeNode;
  var rememberSnapshotNode = helpers.rememberSnapshotNode;
  var isContentEditableElement = helpers.isContentEditableElement;
  var editableText = helpers.editableText;
  var observePage = helpers.observePage;
  var startSnapshot = helpers.startSnapshot;
  var snapshotState = helpers.snapshotState;
  var resolveTarget = helpers.resolveTarget;
  var performType = helpers.performType;
  var isDisabled = helpers.isDisabled;
  var isReadOnly = helpers.isReadOnly;
  var performClick = helpers.performClick;

  // This is deliberately a narrow, page-contract adapter. The generic
  // WebMount handlers remain available for ordinary pages, while these
  // methods only operate on the currently rendered v4 composer.
  var ZCODE_COMPOSER_SELECTOR = '.chat-composer-region[data-testid="v4-composer"][data-input-routing]';
  var ZCODE_INPUT_SELECTOR = '[data-testid="v4-composer-input"][data-lexical-editor="true"]';
  var ZCODE_SEND_SELECTOR = 'button[type="submit"][data-testid="v4-composer-send"]';
  var ZCODE_TASK_SELECTOR = '[data-testid^="v4-session-pane-"][data-session-id]';
  var zcodeTicket = null;
  var zcodeTicketSeq = 0;

  function zcodeError(code, message, hint, extra) {
    var error = { code: code, message: message, hint: hint || null };
    if (extra) {
      for (var key in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, key)) error[key] = extra[key];
      }
    }
    return { ok: false, error: error };
  }

  function zcodeTargetSummary(el, ref) {
    return {
      ref: ref || null,
      tag: (el.tagName || '').toLowerCase(),
      role: roleOf(el),
      rect: rectOf(el),
      visible: isVisible(el),
      css: nodePath(el),
    };
  }

  function zcodeIsVisible(el) {
    return isVisible(el) && String(el.getAttribute && el.getAttribute('aria-hidden') || '').toLowerCase() !== 'true';
  }

  function zcodeSnapshotRefForElement(el) {
    var entries = window.__amberWmSnapshot && window.__amberWmSnapshot.entries;
    if (!entries) return null;
    for (var ref in entries) {
      if (Object.prototype.hasOwnProperty.call(entries, ref) && entries[ref].el === el) return ref;
    }
    return null;
  }

  function zcodeRememberTarget(el) {
    var ref = zcodeSnapshotRefForElement(el);
    if (!ref) ref = rememberSnapshotNode(el, describeNode(el, { text: false })).ref;
    return zcodeTargetSummary(el, ref);
  }

  function zcodeAttachmentCount(composer) {
    var kinds = composer.querySelectorAll('[data-composer-attachment-kind]');
    if (kinds.length > 0) return kinds.length;
    var rows = composer.querySelectorAll('[data-composer-file-attachments-row],[data-composer-context-attachments-row]');
    for (var i = 0; i < rows.length; i++) {
      // These rows are rendered only when a file, context, or reference is
      // present. Keep the check structural so hidden chip text is never read.
      if (rows[i].querySelector('*') || String(rows[i].textContent || '').trim()) return 1;
    }
    return 0;
  }

  function zcodeTaskForComposer(composer) {
    var task = composer && composer.closest ? composer.closest(ZCODE_TASK_SELECTOR) : null;
    if (!task || !task.getAttribute('data-session-id')) return null;
    return { node: task, id: String(task.getAttribute('data-session-id')) };
  }

  function zcodeFindComposer() {
    var composers = Array.prototype.slice.call(document.querySelectorAll(ZCODE_COMPOSER_SELECTOR))
      .filter(zcodeIsVisible);
    if (composers.length === 0) {
      return zcodeError(
        'zcode_unsupported',
        'visible ZCode v4 composer was not found',
        'Open the ZCode v4 chat page with one visible composer.',
        { composer_count: 0 },
      );
    }
    if (composers.length !== 1) {
      return zcodeError(
        'zcode_unsupported',
        'ZCode v4 requires exactly one visible composer',
        'Close hidden or duplicate chat panes, then read the page again.',
        { composer_count: composers.length },
      );
    }
    var composer = composers[0];
    var task = zcodeTaskForComposer(composer);
    if (!task) {
      return zcodeError(
        'zcode_unsupported',
        'ZCode v4 composer is not attached to a session pane',
        'Open the intended ZCode task and read the page again.',
      );
    }
    var routing = String(composer.getAttribute('data-input-routing') || '');
    if (routing !== 'startNow' && routing !== 'enqueue' && routing !== 'reject') {
      return zcodeError(
        'zcode_unsupported',
        'ZCode composer has an unknown input routing mode',
        'Refresh the ZCode page and use a supported v4 composer.',
      );
    }
    var inputs = Array.prototype.slice.call(composer.querySelectorAll(ZCODE_INPUT_SELECTOR))
      .filter(zcodeIsVisible)
      .filter(function (el) { return el.getAttribute('role') === 'textbox' && isContentEditableElement(el); });
    if (inputs.length !== 1) {
      return zcodeError(
        'zcode_unsupported',
        'ZCode v4 composer must contain exactly one visible Lexical input',
        'Read the page again after the composer finishes rendering.',
        { input_count: inputs.length },
      );
    }
    var submits = routing === 'startNow'
      ? Array.prototype.slice.call(composer.querySelectorAll(ZCODE_SEND_SELECTOR)).filter(zcodeIsVisible)
      : [];
    if (submits.length > 1) {
      return zcodeError(
        'zcode_unsupported',
        'ZCode v4 composer has multiple visible submit buttons',
        'Wait for the composer to settle, then read the page again.',
        { submit_count: submits.length },
      );
    }
    return {
      ok: true,
      composer: composer,
      task: task,
      input: inputs[0],
      send: submits.length === 1 ? submits[0] : null,
      routing: routing,
      draft: editableText(inputs[0]),
      attachment_count: zcodeAttachmentCount(composer),
    };
  }

  function zcodeRead(args) {
    args = args || {};
    // A read refresh invalidates any prepared side effect. The typed draft is
    // intentionally left in the page so the user can inspect or clear it.
    var discardedTicket = zcodeTicket !== null;
    zcodeTicket = null;
    var observation;
    if (args.include_page_text === true) {
      observation = observePage(args);
    } else {
      var compactSnapshotId = startSnapshot();
      var compactPage = snapshotState();
      compactPage.snapshot_id = compactSnapshotId;
      observation = { mode: 'zcode_read', page: compactPage };
    }
    var snapshotId = observation.interactive && observation.interactive.snapshot_id != null
      ? observation.interactive.snapshot_id
      : observation.snapshot_id != null
        ? observation.snapshot_id
        : observation.page && observation.page.snapshot_id;
    var uiTree = null;
    if (typeof window.__amberZCodeUiTree === 'function') {
      try {
        uiTree = window.__amberZCodeUiTree(args || {}, {
          describe: function (el) { return describeNode(el, { text: true }); },
          remember: rememberSnapshotNode,
          isVisible: isVisible,
          snapshotId: snapshotId,
        });
      } catch (e) {
        uiTree = { ok: false, error: { code: 'zcode_ui_tree_failed', message: String(e && e.message || e) } };
      }
    }
    var info = zcodeFindComposer();
    var zcode = {
      supported: info.ok === true,
      input_routing: info.ok ? info.routing : null,
      draft_chars: info.ok ? info.draft.length : 0,
      remote_task_id: info.ok ? info.task.id : null,
      composer_target: null,
      input_target: null,
      send_target: null,
      send_available: false,
      snapshot_id: snapshotId,
    };
    if (discardedTicket) {
      zcode.ticket_discarded = true;
      zcode.draft_retained = true;
    }
    if (!info.ok) {
      zcode.error = info.error;
      return { observation: observation, ui_tree: uiTree, zcode: zcode };
    }
    // The composer target is the Lexical input itself. Kotlin passes this ref
    // back as input_target; the outer composer remains an internal guard.
    zcode.composer_target = zcodeRememberTarget(info.input);
    zcode.input_target = zcode.composer_target;
    if (info.send) {
      zcode.send_target = zcodeRememberTarget(info.send);
      zcode.send_available = true;
    }
    return { observation: observation, ui_tree: uiTree, zcode: zcode };
  }

  function zcodeTargetRef(value) {
    if (value && typeof value === 'object' && value.ref != null) return String(value.ref);
    if (value == null) return null;
    return String(value);
  }

  function zcodeResolveRef(value, snapshotId) {
    var ref = zcodeTargetRef(value);
    if (!ref) return zcodeError('missing_target', 'ZCode target ref is required', 'Call zcode_read and pass its snapshot target ref.');
    if (snapshotId == null) return zcodeError('missing_snapshot', 'snapshot_id is required for a ZCode target ref', 'Pass the snapshot_id returned by zcode_read.');
    return resolveTarget({ target: ref, snapshot_id: snapshotId }, {
      requireUnique: true,
      allowFingerprintFallback: false,
      requireStableRect: true,
    });
  }

  function zcodePrepare(args) {
    args = args || {};
    if (typeof args.text !== 'string') throw new Error('zcode_prepare requires text string');
    if (args.text.trim().length === 0) return zcodeError('empty_text', 'ZCode prompt must not be empty', 'Pass a non-empty prompt.');
    if (zcodeTicket !== null) {
      return zcodeError('ticket_exists', 'a ZCode prepare ticket is already pending', 'Consume the existing ticket with zcode_send before preparing another prompt.');
    }
    var snapshotId = args.snapshot_id;
    var info = zcodeFindComposer();
    if (!info.ok) return info;
    var remoteTaskId = zcodeTargetRef(args.remote_task_id);
    if (!remoteTaskId) {
      return zcodeError('missing_remote_task_id', 'remote_task_id is required for ZCode prepare', 'Pass the remote_task_id returned by zcode_read.');
    }
    if (remoteTaskId !== info.task.id) {
      return zcodeError('remote_task_mismatch', 'remote_task_id does not match the current ZCode task', 'Read the current page and prepare again.');
    }
    if (info.routing !== 'startNow') {
      return zcodeError(
        'input_routing',
        'ZCode composer is not ready for an immediate prompt',
        'Wait until data-input-routing is startNow; enqueue and reject are not eligible for automatic ask.',
        { input_routing: info.routing },
      );
    }
    if (!info.send) {
      return zcodeError('send_unavailable', 'ZCode send button is not available', 'Wait for the idle composer and call zcode_read again.');
    }
    if (info.attachment_count > 0) {
      return zcodeError('attachments_present', 'ZCode composer contains attachments or references', 'Remove attachments and references before asking an unambiguous prompt.', { attachment_count: info.attachment_count });
    }
    if (info.draft.trim().length > 0) {
      return zcodeError('draft_not_empty', 'ZCode composer already contains draft text', 'Read the current page and resolve the draft before preparing a prompt.', { draft_chars: info.draft.length });
    }
    var inputRef = zcodeTargetRef(args.input_target);
    var sendRef = zcodeTargetRef(args.send_target);
    var inputResolved = zcodeResolveRef(inputRef, snapshotId);
    if (!inputResolved.ok) return inputResolved;
    var sendResolved = zcodeResolveRef(sendRef, snapshotId);
    if (!sendResolved.ok) return sendResolved;
    if (inputResolved.el !== info.input) {
      return zcodeError('target_mismatch', 'input_target is not the current ZCode v4 composer input', 'Call zcode_read again and use its input_target.ref.');
    }
    if (sendResolved.el !== info.send) {
      return zcodeError('target_mismatch', 'send_target is not the current ZCode v4 submit button', 'Call zcode_read again and use its send_target.ref.');
    }
    var typed = performType({ target: inputRef, snapshot_id: snapshotId, text: args.text, clear: true });
    if (!typed.ok) return typed;
    var ticket = 'zcode_ticket_' + window.__amberWmRealmId + '_' + (++zcodeTicketSeq);
    zcodeTicket = {
      id: ticket,
      composer: info.composer,
      task: info.task,
      task_id: info.task.id,
      input: info.input,
      send: info.send,
      url: location.href,
      text: args.text,
    };
    return {
      ok: true,
      ticket: ticket,
      snapshot_id: snapshotId,
      text_chars: args.text.length,
      input_target: zcodeTargetSummary(info.input, inputRef),
      send_target: zcodeTargetSummary(info.send, sendRef),
    };
  }

  function zcodeSendFailure(result, draftRetained) {
    if (result && result.ok === false) result.draft_retained = draftRetained !== false;
    return result;
  }

  function zcodeSend(args) {
    args = args || {};
    var requested = zcodeTargetRef(args.ticket);
    if (!requested) return zcodeError('missing_ticket', 'ZCode ticket is required', 'Call zcode_prepare first.');
    if (zcodeTicket === null) return zcodeError('ticket_used', 'ZCode prepare ticket is no longer available', 'Prepare a new prompt after reading the current page.');
    if (requested !== zcodeTicket.id) return zcodeError('ticket_mismatch', 'ZCode ticket does not match the pending prepare operation', 'Use the exact ticket returned by zcode_prepare.');
    // A ticket is single-use even when a later validation fails. This prevents
    // a caller from blindly retrying a potentially changed composer.
    var ticket = zcodeTicket;
    zcodeTicket = null;
    if (location.href !== ticket.url) return zcodeSendFailure(zcodeError('ticket_stale', 'ZCode page URL changed after prepare', 'Read the current page and prepare again.'), false);
    var info = zcodeFindComposer();
    if (!info.ok) return zcodeSendFailure(info, false);
    var sameComposerDom = info.composer === ticket.composer && info.input === ticket.input && info.send === ticket.send;
    if (!sameComposerDom || info.task.node !== ticket.task.node || info.task.id !== ticket.task_id) {
      return zcodeSendFailure(zcodeError('ticket_stale', 'ZCode composer or remote task changed after prepare', 'Read the current page and prepare again.'), sameComposerDom);
    }
    if (info.routing !== 'startNow') {
      return zcodeSendFailure(zcodeError('input_routing', 'ZCode composer is no longer ready for an immediate prompt', 'Read the current page and prepare again.', { input_routing: info.routing }));
    }
    if (info.attachment_count > 0) {
      return zcodeSendFailure(zcodeError('attachments_present', 'ZCode composer contains attachments or references', 'Read the current page and prepare again.'));
    }
    if (editableText(info.input) !== ticket.text) {
      return zcodeSendFailure(zcodeError('draft_changed', 'ZCode draft changed after prepare', 'Read the current page and prepare again.'));
    }
    if (isDisabled(info.input) || isReadOnly(info.input)) {
      return zcodeSendFailure(zcodeError('input_unavailable', 'ZCode input is disabled or read-only', 'Read the current page and prepare again.'));
    }
    if (isDisabled(info.send)) {
      return zcodeSendFailure(zcodeError('send_disabled', 'ZCode send button is disabled', 'Read the current page and prepare again.'));
    }
    // The old ref is intentionally not reused for the side effect. Create a
    // fresh snapshot entry immediately before the one allowed click.
    var freshSnapshotId = startSnapshot();
    var freshSend = zcodeRememberTarget(info.send);
    var clicked = performClick({ target: freshSend.ref, snapshot_id: freshSnapshotId });
    if (!clicked.ok) return zcodeSendFailure({ ok: false, dispatched: false, error: clicked.error });
    return {
      ok: true,
      dispatched: true,
      ticket: ticket.id,
      snapshot_id: freshSnapshotId,
      target: zcodeTargetSummary(info.send, freshSend.ref),
    };
  }

  return { read: zcodeRead, prepare: zcodePrepare, send: zcodeSend };
}
