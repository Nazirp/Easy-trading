// Trading journal on the demo trading page.
//
// The journal is its own full-width section at the bottom of the page,
// opened and closed by the "Journal" button under the Place order box. Inside it:
//
//   * the list of entries (newest first, each named by its first line);
//   * opening, writing or editing an entry REPLACES the list until you go
//     back ("← All entries");
//   * saving, editing and deleting all end the same way: back on the list,
//     already updated, with the entry you just changed highlighted.
//
// Unsaved changes: leaving an editor with changes asks "Keep editing /
// Discard / Save". Nothing is kept silently -- EXCEPT in the cases where
// the person can't answer that question:
//   1. they walked away: no mouse/keyboard activity for IDLE_MS while an
//      editor has unsaved text. The server ends a login after 30 minutes
//      with no requests (backend application.yml), so the backup is taken
//      well before that could happen;
//   2. they got signed out: manually (the Log out button), or automatically
//      (a 401 from the server, which flips the page to its signed-out gate);
//   3. they went to another page of the site, e.g. the "← Search" link --
//      kept instead of the browser's "leave site?".
// Those backups live in this browser (localStorage, one per account) --
// the backend has no draft concept -- and are offered back ("Continue
// writing" / "Discard it") the next time the journal is opened.
//
// Backend: /api/journal (backend/CONTRACTS.md "Trading journal").
// An entry is { id, body, symbol, tradeId, createdAt, updatedAt }: text
// only, no pictures/files/links fields. A link typed into the text is made
// clickable when reading. No instrument picker -- BTC/USD is the only
// instrument on this page; an entry that links a trade gets its instrument
// from the trade (server-side). Timestamps are real zoned instants: never
// append a "Z".
(function () {
  "use strict";

  const TRADE_SYMBOL = "BTC/USD";
  const DRAFT_KEY_PREFIX = "easytrading.journalDraft.";
  const IDLE_MS = 10 * 60 * 1000;      // "left the page open too long"
  const IDLE_CHECK_MS = 30 * 1000;
  const HIGHLIGHT_MS = 2500;
  const LIST_VISIBLE = 5;              // entries shown before the list scrolls

  // ---- DOM ----------------------------------------------------------------

  function $(id) { return document.getElementById(id); }

  const chartSection = $("dt-chart-section");

  const openButton = $("jr-open");
  const openDraftDot = $("jr-open-draft");

  const section = $("jr-section");
  const newButton = $("jr-new");
  const closeButton = $("jr-close");

  const restoreBox = $("jr-restore");
  const restoreText = $("jr-restore-text");
  const restoreYes = $("jr-restore-yes");
  const restoreDiscard = $("jr-restore-discard");

  const listView = $("jr-list-view");
  const searchInput = $("jr-search");
  const dateFilter = $("jr-filter-date");
  const resultFilter = $("jr-filter-result");
  const listMessage = $("jr-list-message");
  const listEl = $("jr-list");
  const emptyEl = $("jr-empty");

  const detail = $("jr-detail");
  const backButton = $("jr-back");
  const detailMeta = $("jr-detail-meta");

  const readView = $("jr-read");
  const readLinks = $("jr-read-links");
  const readBody = $("jr-read-body");
  const editButton = $("jr-edit");
  const deleteButton = $("jr-delete");
  const deleteConfirm = $("jr-delete-confirm");
  const deleteText = $("jr-delete-text");
  const deleteCancel = $("jr-delete-cancel");
  const deleteYes = $("jr-delete-yes");

  const form = $("jr-form");
  const tradeField = $("jr-trade-field");
  const tradeSelect = $("jr-trade");
  const tradeDirectionFilter = $("jr-trade-direction");
  const tradeDateFilter = $("jr-trade-date");
  const formLinks = $("jr-form-links");
  const bodyInput = $("jr-body");
  const bodyError = $("jr-body-error");
  const formError = $("jr-form-error");
  const idleNote = $("jr-idle-note");
  const cancelButton = $("jr-cancel");
  const saveButton = $("jr-save");
  const unsavedBar = $("jr-unsaved");
  const unsavedStay = $("jr-unsaved-stay");
  const unsavedDiscard = $("jr-unsaved-discard");
  const unsavedSave = $("jr-unsaved-save");

  const DELETE_PROMPT = deleteText.textContent;

  // ---- State --------------------------------------------------------------

  let username = null;       // who is signed in; null = nobody
  let loadState = "idle";    // "idle" | "loading" | "ready" | "error"
  let entries = [];          // newest first
  let trades = [];           // this user's BTC/USD trades, newest first
  let tradesById = new Map();

  let isOpen = false;        // the journal section is showing
  let mode = "list";         // "list" | "read" | "new" | "edit"
  let currentId = null;      // entry shown in "read" / edited in "edit"
  let formTradeId = null;    // new entry's trade link
  let baseline = null;       // what "unsaved changes" is measured against
  let pendingAction = null;  // what to do once the unsaved-changes question is answered
  let saving = false;
  let highlightId = null;    // entry just saved -- highlighted in the list
  let highlightTimer = null;
  let confirmDeleteId = null; // entry whose row is asking "Delete this entry?"
  let listNotice = null;     // one-off message above the list

  let storedDraft = null;    // a kept draft waiting to be continued or discarded
  let editorBackedUp = false; // this editor's text was backed up while idle
  let lastActivity = Date.now();

  // ---- Small helpers ------------------------------------------------------

  function show(el) { el.hidden = false; }
  function hide(el) { el.hidden = true; }

  function formatPrice(price) {
    return "$" + Number(price).toLocaleString(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
  }

  // Same rule as demo-trading.js: 8 stored decimals, trailing zeros trimmed.
  function formatQuantity(qty) {
    let str = Number(qty).toFixed(8);
    str = str.replace(/0+$/, "").replace(/\.$/, "");
    return str === "" ? "0" : str;
  }

  // createdAt / updatedAt / openedAt are real zoned instants
  // (CONTRACTS.md) -- parsed as-is, never with an appended "Z".
  function formatWhen(iso) {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    const opts = { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" };
    if (d.getFullYear() !== new Date().getFullYear()) opts.year = "numeric";
    return d.toLocaleString(undefined, opts);
  }

  function localDateKey(iso) {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return "";
    return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") +
      "-" + String(d.getDate()).padStart(2, "0");
  }

  // An entry's name: its first non-empty line.
  function entryName(body) {
    const lines = String(body || "").split("\n");
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i].trim();
      if (line) return line;
    }
    return "";
  }

  function findEntry(id) {
    for (let i = 0; i < entries.length; i++) {
      if (entries[i].id === id) return entries[i];
    }
    return null;
  }

  function sortEntries() {
    entries.sort(function (a, b) { return Date.parse(b.createdAt) - Date.parse(a.createdAt); });
  }

  function badge(text) {
    const el = document.createElement("span");
    el.className = "jr-badge";
    el.textContent = text;
    return el;
  }

  // Same rule as demo-trading.js: the sign follows the ROUNDED value, so
  // nothing ever reads "+$0.00".
  function formatSignedUsd(amount) {
    const shown = Math.round(amount * 100) / 100;
    const sign = shown > 0 ? "+" : shown < 0 ? "\u2212" : "";
    return sign + formatPrice(Math.abs(shown));
  }

  // A trade's result is its `pnl` once closed. While it is open
  // GET /api/trades sends pnl null (CONTRACTS.md) -- its live value is on
  // the chart page, not here -- so it reads "Open", never a number.
  function tradeResult(t) {
    return t.closedAt === null ? "Open" : formatSignedUsd(t.pnl);
  }

  // "LONG 0.0025 BTC @ $76,391.40 · 21 Sep, 10:14 · +$3.23" -- the trade
  // picker's text.
  function tradeText(t) {
    return t.direction + " " + formatQuantity(t.quantity) + " BTC @ " +
      formatPrice(t.entryPrice) + " · " + formatWhen(t.openedAt) + " · " + tradeResult(t);
  }

  // The same, labelled "Your trade" -- a linked trade must read
  // as what the person DID, not as a current price. And how it
  // turned out -- the result is coloured, the direction is not (a short is
  // not a loss).
  function tradeTag(tradeId) {
    const tag = document.createElement("span");
    tag.className = "jr-trade-tag";
    const label = document.createElement("span");
    label.className = "jr-trade-tag-label";
    label.textContent = "Your trade";
    tag.appendChild(label);

    const t = tradesById.get(tradeId);
    if (!t) {
      tag.appendChild(document.createTextNode("#" + tradeId));
      return tag;
    }
    const direction = document.createElement("span");
    direction.className = "jr-trade-tag-direction";
    direction.textContent = t.direction;
    tag.appendChild(direction);
    tag.appendChild(document.createTextNode(
      formatQuantity(t.quantity) + " BTC @ " + formatPrice(t.entryPrice) + " · " + formatWhen(t.openedAt) + " ·"
    ));
    tag.appendChild(resultBadge(t));
    return tag;
  }

  // The trade's result on its own -- in the tag above, and beside "Trade
  // linked" in the list, so an entry shows how its trade went before it is
  // even opened. Coloured by the amount as SHOWN (whole cents), like the
  // history; "Open" in no colour at all.
  function resultBadge(t) {
    const result = document.createElement("span");
    let tone = "is-open";
    if (t.closedAt !== null) {
      const cents = Math.round(t.pnl * 100);
      tone = cents > 0 ? "is-positive" : cents < 0 ? "is-negative" : "is-flat";
    }
    result.className = "jr-result " + tone;
    result.textContent = tradeResult(t);
    return result;
  }

  function fillLinks(container, tradeId) {
    container.replaceChildren();
    if (tradeId != null) container.appendChild(tradeTag(tradeId));
    container.hidden = !container.firstChild;
  }

  // Writes `text` into `container`, turning http(s) links into real,
  // clickable links. Built from text nodes and <a> elements only -- never
  // innerHTML -- so nothing typed into an entry can inject markup.
  const URL_PATTERN = /https?:\/\/[^\s<>"']+/g;
  function renderWithLinks(container, text) {
    container.replaceChildren();
    let last = 0;
    let match;
    URL_PATTERN.lastIndex = 0;
    while ((match = URL_PATTERN.exec(text)) !== null) {
      let url = match[0];
      // Sentence punctuation right after a link isn't part of it.
      const trimmed = url.replace(/[.,;:!?)\]]+$/, "");
      const end = match.index + trimmed.length;
      url = trimmed;
      if (match.index > last) {
        container.appendChild(document.createTextNode(text.slice(last, match.index)));
      }
      const a = document.createElement("a");
      a.href = url;
      a.textContent = url;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.className = "jr-link";
      container.appendChild(a);
      last = end;
      URL_PATTERN.lastIndex = end;
    }
    if (last < text.length) container.appendChild(document.createTextNode(text.slice(last)));
  }

  // ---- Kept drafts (browser storage) ------------------------------------------

  function draftKey() { return DRAFT_KEY_PREFIX + username; }

  function readStoredDraft() {
    if (!username) return null;
    try {
      const raw = window.localStorage.getItem(draftKey());
      const d = raw ? JSON.parse(raw) : null;
      return d && typeof d.body === "string" ? d : null;
    } catch (err) {
      return null;
    }
  }

  function writeStoredDraft(draft) {
    storedDraft = draft;
    if (!username) return;
    try {
      if (draft) window.localStorage.setItem(draftKey(), JSON.stringify(draft));
      else window.localStorage.removeItem(draftKey());
    } catch (err) {
      // Storage blocked (private mode etc.): the draft then only lasts as
      // long as this page does.
    }
  }

  // Backs up whatever the open editor holds. `reason` says why, so the
  // restore message can say it too.
  function keepEditorAsDraft(reason) {
    writeStoredDraft({
      kind: mode === "edit" ? "edit" : "new",
      entryId: mode === "edit" ? currentId : null,
      body: bodyInput.value,
      tradeId: formTradeId,
      savedAt: new Date().toISOString(),
      reason: reason
    });
  }

  // Leaving an editor normally (saved, discarded, or left unchanged): an
  // idle backup taken during this edit is no longer needed.
  function releaseEditorBackup() {
    if (editorBackedUp) {
      writeStoredDraft(null);
      editorBackedUp = false;
    }
    hide(idleNote);
  }

  // ---- Talking to the backend ---------------------------------------------

  async function request(method, url, payload) {
    const options = { method: method, headers: {} };
    if (payload !== undefined) {
      options.headers["Content-Type"] = "application/json";
      options.body = JSON.stringify(payload);
    }
    const response = await fetch(url, options);
    let body = null;
    if (response.status !== 204) {
      try { body = await response.json(); } catch (err) { body = null; }
    }
    return { status: response.status, body: body };
  }

  function errorCode(result) {
    return result.body && result.body.code ? result.body.code : null;
  }

  async function loadEntries() {
    const result = await request("GET", "/api/journal");
    if (result.status === 401) { handleSignedOut("session-expired"); return false; }
    if (result.status !== 200 || !result.body) throw new Error("journal " + result.status);
    entries = Array.isArray(result.body.entries) ? result.body.entries : [];
    sortEntries();
    return true;
  }

  async function loadTrades() {
    try {
      const result = await request("GET", "/api/trades?symbol=" + encodeURIComponent(TRADE_SYMBOL));
      if (result.status !== 200 || !result.body) return;
      trades = Array.isArray(result.body.trades) ? result.body.trades : [];
    } catch (err) {
      return; // best-effort: the picker just stays as it was
    }
    tradesById = new Map();
    trades.forEach(function (t) { tradesById.set(t.id, t); });
  }

  async function loadAll() {
    loadState = "loading";
    render();
    const who = username;
    try {
      await loadTrades();
      const ok = await loadEntries();
      if (who !== username || !ok) return;
      loadState = "ready";
    } catch (err) {
      if (who !== username) return;
      loadState = "error";
    }
    render();
  }

  // ---- Rendering ----------------------------------------------------------

  function filtersActive() {
    return searchInput.value.trim() !== "" || dateFilter.value !== "" || resultFilter.value !== "";
  }

  function visibleEntries() {
    const q = searchInput.value.trim().toLowerCase();
    const day = dateFilter.value;
    const result = resultFilter.value;
    return entries.filter(function (e) {
      if (q && String(e.body).toLowerCase().indexOf(q) === -1) return false;
      if (day && localDateKey(e.createdAt) !== day) return false;
      if (result && resultOf(e) !== result) return false;
      return true;
    });
  }

  // "win" or "loss" for an entry linked to a closed trade, by the sign of the
  // pnl the server sent; null for an unlinked entry or an open trade, so
  // those only show under "All entries".
  function resultOf(e) {
    const t = e.tradeId != null ? tradesById.get(e.tradeId) : null;
    if (!t || t.closedAt === null) return null;
    return t.pnl > 0 ? "win" : t.pnl < 0 ? "loss" : null;
  }

  function renderList() {
    listEl.replaceChildren();
    hide(emptyEl);
    hide(listMessage);

    if (loadState === "loading" && entries.length === 0) {
      listMessage.textContent = "Loading your journal…";
      show(listMessage);
      hide(listEl);
      return;
    }
    if (loadState === "error") {
      listMessage.textContent = "Couldn't load your journal — try again in a moment.";
      show(listMessage);
      hide(listEl);
      return;
    }
    if (listNotice) {
      listMessage.textContent = listNotice;
      show(listMessage);
    }

    visibleEntries().forEach(function (e) {
      const li = document.createElement("li");
      li.className = "jr-row";
      li.dataset.id = String(e.id);
      const item = document.createElement("button");
      item.type = "button";
      item.className = "jr-item" + (e.id === highlightId ? " is-just-saved" : "");

      const name = document.createElement("span");
      name.className = "jr-item-name";
      name.textContent = entryName(e.body);
      item.appendChild(name);

      const meta = document.createElement("span");
      meta.className = "jr-item-meta";
      if (e.tradeId != null) {
        meta.appendChild(badge("Trade linked"));
        const t = tradesById.get(e.tradeId);
        if (t) meta.appendChild(resultBadge(t));
      }
      const when = document.createElement("span");
      when.textContent = formatWhen(e.createdAt);
      meta.appendChild(when);
      // "edited" only when updatedAt is non-null.
      if (e.updatedAt) {
        const edited = document.createElement("span");
        edited.textContent = "· edited";
        meta.appendChild(edited);
      }
      item.appendChild(meta);

      li.appendChild(item);
      li.appendChild(rowActions(e.id));
      listEl.appendChild(li);
    });

    if (listEl.firstChild) {
      show(listEl);
    } else {
      hide(listEl);
      if (entries.length === 0) {
        show(emptyEl);
      } else if (filtersActive()) {
        listMessage.textContent = "No entries match these filters.";
        show(listMessage);
      }
    }
    fitList();
  }

  // Edit and Delete on every row, so neither needs the entry opened first.
  // Delete asks once, in the row itself -- the same question the entry's
  // own Delete button asks, without leaving the list.
  function rowActions(id) {
    const actions = document.createElement("div");
    actions.className = "jr-row-actions";
    if (id === confirmDeleteId) {
      const question = document.createElement("span");
      question.className = "jr-row-question";
      question.textContent = "Delete this entry?";
      actions.appendChild(question);
      actions.appendChild(rowButton("Cancel", "keep", ""));
      actions.appendChild(rowButton("Delete", "confirm-delete", " jr-btn-danger"));
    } else {
      actions.appendChild(rowButton("Edit", "edit", ""));
      actions.appendChild(rowButton("Delete", "delete", " jr-btn-danger"));
    }
    return actions;
  }

  function rowButton(label, action, extraClass) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "jr-btn jr-row-btn" + extraClass;
    button.dataset.action = action;
    button.textContent = label;
    return button;
  }

  // At most LIST_VISIBLE entries show at once; the rest scroll inside the
  // list. Measured rather than a fixed CSS height so it stays exactly five
  // whole rows whatever the font size. Skipped while the list isn't laid
  // out (journal closed) -- it is measured again when it opens.
  function fitList() {
    const last = listEl.children[LIST_VISIBLE - 1];
    const overflowing = listEl.children.length > LIST_VISIBLE && last.offsetHeight > 0;
    listEl.style.maxHeight = overflowing ? (last.offsetTop + last.offsetHeight) + "px" : "";
  }

  function renderRead() {
    const e = findEntry(currentId);
    if (!e) return;
    let meta = "Written " + formatWhen(e.createdAt);
    if (e.updatedAt) meta += " · edited " + formatWhen(e.updatedAt);
    detailMeta.textContent = meta;
    fillLinks(readLinks, e.tradeId);
    renderWithLinks(readBody, e.body);
  }

  function renderRestore() {
    const d = storedDraft;
    // Not while that same text is still open in the editor (an idle backup).
    openDraftDot.hidden = !d || editorBackedUp;
    if (!d || mode === "new" || mode === "edit") {
      hide(restoreBox);
      return;
    }
    const why = d.reason === "idle" ? "the page was left idle"
      : d.reason === "session-expired" ? "your session expired"
      : d.reason === "left-page" ? "you went to another page"
      : "you signed out";
    const what = d.kind === "edit" ? "an edit" : "a new entry";
    const name = entryName(d.body);
    restoreText.textContent = "You have unsaved text from " + formatWhen(d.savedAt) +
      " (" + what + "), kept because " + why + (name ? ": “" + name + "”" : "") + ".";
    show(restoreBox);
  }

  function render() {
    section.hidden = !isOpen || chartSection.hidden;
    openButton.setAttribute("aria-expanded", String(isOpen));
    openButton.classList.toggle("is-open", isOpen);

    const inDetail = mode !== "list";
    listView.hidden = inDetail;
    detail.hidden = !inDetail;
    readView.hidden = mode !== "read";
    form.hidden = mode !== "new" && mode !== "edit";
    if (mode === "read") renderRead();

    newButton.disabled = loadState !== "ready";
    renderRestore();
    renderList();
  }

  // ---- The editor -----------------------------------------------------------

  // The picker, narrowed by its two filters: long or short, and a day the
  // trade was open on. The trade already chosen always stays in the list,
  // so changing a filter never silently drops a choice the person made.
  function rebuildTradeSelect() {
    tradeSelect.replaceChildren();
    const none = document.createElement("option");
    none.value = "";
    none.textContent = "No trade";
    tradeSelect.appendChild(none);

    const direction = tradeDirectionFilter.value;
    const day = tradeDateFilter.value;
    const shown = trades.filter(function (t) {
      return t.id === formTradeId ||
        ((!direction || t.direction === direction) && (!day || openOnDay(t, day)));
    });
    shown.forEach(function (t) {
      const opt = document.createElement("option");
      opt.value = String(t.id);
      opt.textContent = tradeText(t);
      tradeSelect.appendChild(opt);
    });
    if (shown.length === 0) {
      const nothing = document.createElement("option");
      nothing.disabled = true;
      nothing.textContent = "No trades match these filters";
      tradeSelect.appendChild(nothing);
    }

    tradeSelect.value = formTradeId != null ? String(formTradeId) : "";
    if (tradeSelect.value === "" && formTradeId != null) formTradeId = null;
  }

  // A new entry, or an edit of one with no trade yet (a link can be added,
  // never changed -- CONTRACTS.md). Filters start cleared each time.
  // No trades yet is normal -- the picker is simply absent.
  function showTradePicker() {
    tradeDirectionFilter.value = "";
    tradeDateFilter.value = "";
    rebuildTradeSelect();
    tradeField.hidden = trades.length === 0;
  }

  function pickerApplies() {
    if (mode === "new") return true;
    const e = mode === "edit" ? findEntry(currentId) : null;
    return e !== null && e.tradeId == null;
  }

  // Was the trade open at any point on `day` ("YYYY-MM-DD", local)? A trade
  // held overnight belongs to both days. Same rule as the history's filter.
  function openOnDay(t, day) {
    return localDateKey(t.openedAt) <= day && day <= localDateKey(t.closedAt || new Date());
  }

  function isDirty() {
    if (mode !== "new" && mode !== "edit") return false;
    if (bodyInput.value !== baseline.body) return true;
    return formTradeId !== baseline.tradeId;
  }

  function clearFormMessages() {
    hide(bodyError);
    hide(formError);
    hide(unsavedBar);
    hide(idleNote);
  }

  // `start` optionally pre-fills the editor: a kept draft's text, or just a
  // trade (a trade from the history, "Write a journal entry about this
  // trade"). The baseline stays the EMPTY entry, so either still counts as
  // unsaved.
  function enterNew(start) {
    listNotice = null;
    mode = "new";
    currentId = null;
    editorBackedUp = false;
    formTradeId = start && start.tradeId != null && tradesById.has(start.tradeId) ? start.tradeId : null;
    baseline = { body: "", tradeId: null };
    bodyInput.value = start ? start.body : "";
    detailMeta.textContent = start && start.body ? "New entry · continued from your unsaved text" : "New entry";
    hide(formLinks);
    clearFormMessages();
    showTradePicker();
    render();
    bodyInput.focus({ preventScroll: true });
  }

  // `start` is a kept draft ({body, tradeId}) being continued, if any.
  function enterEdit(id, start) {
    const e = findEntry(id);
    if (!e) return;
    listNotice = null;
    confirmDeleteId = null;
    mode = "edit";
    currentId = id;
    editorBackedUp = false;
    baseline = { body: e.body, tradeId: null };
    bodyInput.value = start ? start.body : e.body;
    detailMeta.textContent = start ? "Editing · continued from your unsaved text" : "Editing";
    // A link can be added to an entry without one, never changed
    // (CONTRACTS.md, PATCH /api/journal/{id}): no trade yet -> the picker;
    // a trade already -> shown as it is.
    if (e.tradeId == null) {
      formTradeId = start && start.tradeId != null && tradesById.has(start.tradeId) ? start.tradeId : null;
      hide(formLinks);
      showTradePicker();
    } else {
      formTradeId = null;
      hide(tradeField);
      fillLinks(formLinks, e.tradeId);
    }
    clearFormMessages();
    render();
    bodyInput.focus({ preventScroll: true });
  }

  function enterRead(id) {
    listNotice = null;
    confirmDeleteId = null;
    releaseEditorBackup();
    mode = "read";
    currentId = id;
    hide(deleteConfirm);
    deleteText.textContent = DELETE_PROMPT;
    render();
  }

  // Back to the list. `changedId` (just saved) is highlighted and scrolled
  // into view, so adding and editing visibly land in the list the same way
  // deleting does.
  function enterList(changedId) {
    releaseEditorBackup();
    mode = "list";
    currentId = null;
    if (changedId != null) {
      highlightId = changedId;
      clearTimeout(highlightTimer);
      highlightTimer = setTimeout(function () {
        highlightId = null;
        const el = listEl.querySelector(".jr-item.is-just-saved");
        if (el) el.classList.remove("is-just-saved");
      }, HIGHLIGHT_MS);
    }
    render();
    if (changedId != null) {
      const el = listEl.querySelector('.jr-row[data-id="' + changedId + '"]');
      if (el) el.scrollIntoView({ block: "nearest" });
    }
  }

  // Every way out of an editor goes through here. With unsaved changes the
  // person chooses: keep editing, discard, or save.
  function requestLeave(action) {
    if (!isDirty()) {
      hide(unsavedBar);
      action();
      return;
    }
    pendingAction = action;
    show(unsavedBar);
    unsavedSave.focus();
  }

  // ---- Saving, deleting -----------------------------------------------------

  function setSaving(value) {
    saving = value;
    saveButton.disabled = value;
    unsavedSave.disabled = value;
    saveButton.textContent = value ? "Saving…" : "Save entry";
  }

  // Resolves to the saved entry, or null if it wasn't saved (the reason is
  // already on screen).
  async function saveEditor() {
    if (saving) return null;
    hide(bodyError);
    hide(formError);

    const text = bodyInput.value;
    // Empty and whitespace-only are blocked before sending.
    if (text.trim() === "") {
      hide(unsavedBar);
      bodyError.textContent = "An entry needs some text.";
      show(bodyError);
      bodyInput.focus();
      return null;
    }

    const creating = mode === "new";
    const editingId = currentId;
    setSaving(true);
    let result;
    try {
      result = creating
        ? await request("POST", "/api/journal", { body: text, symbol: null, tradeId: formTradeId })
        : await request("PATCH", "/api/journal/" + encodeURIComponent(editingId), { body: text, tradeId: formTradeId });
    } catch (networkErr) {
      setSaving(false);
      hide(unsavedBar);
      formError.textContent = "Couldn't reach the server — nothing was saved. Try again in a moment.";
      show(formError);
      return null;
    }
    setSaving(false);

    if (result.status === 401) { handleSignedOut("session-expired"); return null; }

    if ((creating ? result.status === 201 : result.status === 200) && result.body) {
      const saved = result.body;
      const idx = entries.findIndex(function (e) { return e.id === saved.id; });
      if (idx !== -1) entries[idx] = saved; else entries.push(saved);
      sortEntries();
      return saved;
    }

    hide(unsavedBar);
    // Branch on `code`, never on `message`.
    const code = errorCode(result);
    if (code === "INVALID_BODY") {
      bodyError.textContent = "An entry needs some text.";
      show(bodyError);
      bodyInput.focus();
    } else if (code === "NOT_FOUND" && formTradeId !== null && !(await tradeStillThere(formTradeId))) {
      // A 404 names the entry OR the trade (the backend says the same for
      // both on purpose). The trade has gone from this user's list, so it
      // was the trade: drop the link and let them save without it.
      formTradeId = null;
      rebuildTradeSelect();
      formError.textContent = "That trade isn't available any more, so the link has been removed. Save again to keep the text without it.";
      show(formError);
    } else if (code === "NOT_FOUND" && !creating) {
      entryGone(editingId);
    } else if (code === "ALREADY_LINKED") {
      // Linked to a trade meanwhile (another tab). A link can't be changed,
      // so show the one it has; the text is still here to save.
      try { await loadEntries(); } catch (err) { /* the message below still holds */ }
      const e = findEntry(editingId);
      formTradeId = null;
      hide(tradeField);
      if (e) fillLinks(formLinks, e.tradeId);
      formError.textContent = "This entry was linked to a trade in the meantime, and a link can't be changed. Save again to keep your text.";
      show(formError);
    } else {
      formError.textContent = "Something went wrong — nothing was saved. Try again.";
      show(formError);
    }
    return null;
  }

  async function tradeStillThere(id) {
    await loadTrades();
    return tradesById.has(id);
  }

  // Deletes one entry, from the entry's own view or straight from its row
  // in the list. Resolves to null when the page has already moved on
  // (deleted, already gone, or signed out), or to the sentence to show when
  // the entry was NOT deleted.
  async function deleteEntry(id) {
    let result;
    try {
      result = await request("DELETE", "/api/journal/" + encodeURIComponent(id));
    } catch (networkErr) {
      return "Couldn't reach the server — the entry was not deleted. Try again?";
    }

    if (result.status === 401) { handleSignedOut("session-expired"); return null; }
    if (result.status === 204) {
      entries = entries.filter(function (e) { return e.id !== id; });
      confirmDeleteId = null;
      hide(deleteConfirm);
      enterList();
      return null;
    }
    if (errorCode(result) === "NOT_FOUND") {
      confirmDeleteId = null;
      entryGone(id);
      return null;
    }
    return "Something went wrong — the entry was not deleted. Try again?";
  }

  async function deleteCurrent() {
    deleteYes.disabled = true;
    const failure = await deleteEntry(currentId);
    deleteYes.disabled = false;
    if (failure) deleteText.textContent = failure;
  }

  async function deleteFromList(id, button) {
    button.disabled = true;
    const failure = await deleteEntry(id);
    if (!failure) return;
    listNotice = failure;
    renderList();
  }

  // A 404 on PATCH/DELETE: the entry is gone (or was never this user's --
  // the backend deliberately doesn't say which). Refresh and say so.
  function entryGone(id) {
    entries = entries.filter(function (e) { return e.id !== id; });
    hide(deleteConfirm);
    enterList();
    listNotice = "That entry no longer exists — the list has been refreshed.";
    render();
    loadEntries().then(render).catch(function () {});
  }

  // Signed out while the page is open -- by the Log out button, or by the
  // server (401). If an editor had unsaved text, it is kept as a draft:
  // the person had no chance to answer "discard or save".
  function handleSignedOut(reason) {
    if (username && isDirty()) keepEditorAsDraft(reason);
    else if (editorBackedUp) writeStoredDraft(null); // text was put back as it was
    editorBackedUp = false;
    mode = "list";
    currentId = null;
    pendingAction = null;
    bodyInput.value = "";
    clearFormMessages();
    isOpen = false;
    listNotice = null;
    render();
  }

  // ---- Open / close ---------------------------------------------------------

  function openJournal() {
    isOpen = true;
    render();
    section.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function closeJournal() {
    requestLeave(function () {
      if (mode === "new" || mode === "edit") enterList();
      isOpen = false;
      render();
      openButton.focus({ preventScroll: true });
    });
  }

  // ---- Idle backup ----------------------------------------------------------

  function noteActivity() {
    lastActivity = Date.now();
  }

  ["pointerdown", "pointermove", "keydown", "wheel", "touchstart", "scroll"].forEach(function (type) {
    document.addEventListener(type, noteActivity, { passive: true, capture: true });
  });

  setInterval(function () {
    if (!isDirty() || editorBackedUp) return;
    if (Date.now() - lastActivity < IDLE_MS) return;
    keepEditorAsDraft("idle");
    editorBackedUp = true;
    idleNote.textContent = "No activity for a while, so a copy of this text was kept in this browser — " +
      "if you get signed out, you can continue it later. Save or discard as usual.";
    show(idleNote);
    render();
  }, IDLE_CHECK_MS);

  // ---- Events ---------------------------------------------------------------

  openButton.addEventListener("click", function () {
    if (isOpen) closeJournal(); else openJournal();
  });
  closeButton.addEventListener("click", closeJournal);

  newButton.addEventListener("click", function () {
    if (mode === "new") { bodyInput.focus(); return; }
    requestLeave(function () { enterNew(null); });
  });

  restoreYes.addEventListener("click", function () {
    const d = storedDraft;
    if (!d) return;
    writeStoredDraft(null); // the text now lives in the editor
    if (d.kind === "edit" && findEntry(d.entryId)) {
      enterEdit(d.entryId, { body: d.body, tradeId: d.tradeId });
    } else {
      enterNew({ body: d.body, tradeId: d.tradeId });
      if (d.kind === "edit") {
        detailMeta.textContent = "New entry · the entry you were editing no longer exists, so this will be saved as a new one";
      }
    }
  });
  restoreDiscard.addEventListener("click", function () {
    writeStoredDraft(null);
    render();
  });

  // One listener for every row: the entry itself opens it, the buttons
  // beside it say what they do in data-action.
  listEl.addEventListener("click", function (event) {
    const button = event.target.closest("button");
    if (!button) return;
    const id = Number(button.closest(".jr-row").dataset.id);
    const action = button.dataset.action;
    if (!action) {
      enterRead(id);
    } else if (action === "edit") {
      enterEdit(id);
    } else if (action === "delete" || action === "keep") {
      confirmDeleteId = action === "delete" ? id : null;
      listNotice = null;
      renderList();
    } else if (action === "confirm-delete") {
      deleteFromList(id, button);
    }
  });
  window.addEventListener("resize", fitList);

  searchInput.addEventListener("input", renderList);
  dateFilter.addEventListener("change", renderList);
  resultFilter.addEventListener("change", renderList);

  backButton.addEventListener("click", function () {
    listNotice = null;
    requestLeave(function () { enterList(); });
  });

  editButton.addEventListener("click", function () { enterEdit(currentId); });

  deleteButton.addEventListener("click", function () {
    deleteText.textContent = DELETE_PROMPT;
    show(deleteConfirm);
    deleteCancel.focus();
  });
  deleteCancel.addEventListener("click", function () { hide(deleteConfirm); });
  deleteYes.addEventListener("click", deleteCurrent);

  tradeSelect.addEventListener("change", function () {
    formTradeId = tradeSelect.value === "" ? null : Number(tradeSelect.value);
  });
  tradeDirectionFilter.addEventListener("change", rebuildTradeSelect);
  tradeDateFilter.addEventListener("change", rebuildTradeSelect);

  bodyInput.addEventListener("input", function () {
    hide(bodyError);
    hide(idleNote);
  });

  form.addEventListener("submit", async function (event) {
    event.preventDefault();
    const saved = await saveEditor();
    if (saved) enterList(saved.id);
  });

  cancelButton.addEventListener("click", function () {
    if (mode === "edit") {
      const id = currentId;
      requestLeave(function () { enterRead(id); });
    } else {
      requestLeave(function () { enterList(); });
    }
  });

  unsavedStay.addEventListener("click", function () {
    pendingAction = null;
    hide(unsavedBar);
    bodyInput.focus();
  });
  unsavedDiscard.addEventListener("click", function () {
    const action = pendingAction;
    pendingAction = null;
    hide(unsavedBar);
    releaseEditorBackup();
    if (action) action();
  });
  unsavedSave.addEventListener("click", async function () {
    const action = pendingAction;
    const saved = await saveEditor();
    if (!saved) return; // the reason is on screen; still in the editor
    pendingAction = null;
    hide(unsavedBar);
    // Saved, so the editor is done -- the list shows it, then whatever the
    // person was trying to do (open another entry, close the journal...).
    enterList(saved.id);
    if (action) action();
  });

  // Going to another page of this site (the "← Search" link) with unsaved
  // text: keep it as a draft and let the navigation happen, rather than
  // asking. Links that open a new tab leave this page running, and links
  // that are blocked (e.g. auth.js's locked links) go nowhere -- neither
  // needs a draft.
  let leavingViaLink = false;
  document.addEventListener("click", function (event) {
    if (event.defaultPrevented || event.button !== 0 ||
        event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const a = event.target.closest("a[href]");
    if (!a || (a.target && a.target !== "_self")) return;
    let url;
    try { url = new URL(a.href, window.location.href); } catch (err) { return; }
    if (url.origin !== window.location.origin || url.pathname === window.location.pathname) return;
    if (!isDirty()) return;
    keepEditorAsDraft("left-page");
    editorBackedUp = true;  // if the page somehow stays, leaving the editor normally drops it again
    leavingViaLink = true;
    setTimeout(function () { leavingViaLink = false; }, 3000);
  });

  // Reloading or closing the tab mid-edit: the browser's own "leave site?"
  // prompt (browsers don't allow custom buttons there).
  window.addEventListener("beforeunload", function (event) {
    if (isDirty() && !leavingViaLink) {
      event.preventDefault();
      event.returnValue = "";
    }
  });

  // A trade was opened or closed (demo-trading.js): the picker should
  // offer a new trade straight away, and a linked trade's tag should show
  // its result as soon as it has one.
  document.addEventListener("easytrading:tradeschanged", function () {
    if (!username) return;
    loadTrades().then(function () {
      if (pickerApplies()) {
        rebuildTradeSelect();
        tradeField.hidden = trades.length === 0;
      } else if (mode === "edit") {
        fillLinks(formLinks, findEntry(currentId).tradeId);
      }
      render();
    });
  });

  // "Write a journal entry about this trade", from a trade in the history
  // (demo-trading.js): open the journal on a new entry already linked to
  // it. Unsaved text in an open editor gets the usual question first.
  document.addEventListener("easytrading:journaltrade", function (event) {
    if (!username) return;
    const tradeId = event.detail.tradeId;
    openJournal();
    requestLeave(function () { enterNew({ body: "", tradeId: tradeId }); });
  });

  // Sign-in / sign-out (auth.js).
  document.addEventListener("easytrading:authchange", function (event) {
    const user = event.detail && event.detail.user;
    const next = user ? user.username : null;
    if (next !== null && next === username) return;

    if (username) handleSignedOut("signed-out");

    username = next;
    entries = [];
    trades = [];
    tradesById = new Map();
    highlightId = null;
    searchInput.value = "";
    dateFilter.value = "";
    resultFilter.value = "";
    storedDraft = readStoredDraft();
    if (username) {
      loadAll();
    } else {
      loadState = "idle";
      render();
    }
  });

  // An automatic sign-out: demo-trading.js answers a 401 on its poll by
  // hiding the chart and trading sections and showing its sign-in gate --
  // without an authchange event. Treat that exactly like signing out.
  new MutationObserver(function () {
    if (chartSection.hidden && username) {
      handleSignedOut("session-expired");
      username = null; // a later sign-in (even as the same user) reloads everything
      entries = [];
      loadState = "idle";
      render();
    } else {
      render();
    }
  }).observe(chartSection, { attributes: true, attributeFilter: ["hidden"] });

  render();
})();
