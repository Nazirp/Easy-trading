// Trading journal (SCRUM-82 / UC05) on the demo trading page.
//
// Isna, 2026-09-27 (second layout): the journal is its own full-width
// section at the bottom of the page, opened and closed by the "Journal"
// button under the Place order box. Inside it:
//
//   * the list of entries (newest first, each named by its first line);
//   * opening, writing or editing an entry REPLACES the list until you go
//     back ("← All entries");
//   * saving, editing and deleting all end the same way: back on the list,
//     already updated, with the entry you just changed highlighted.
//
// Unsaved changes: leaving an editor with changes asks "Keep editing /
// Discard / Save". Nothing is kept silently -- EXCEPT in the two cases where
// the person can't answer that question:
//   1. they walked away: no mouse/keyboard activity for IDLE_MS while an
//      editor has unsaved text. The server ends a login after 30 minutes
//      with no requests (backend application.yml), so the backup is taken
//      well before that could happen;
//   2. they got signed out: manually (the Log out button), or automatically
//      (a 401 from the server, which flips the page to its signed-out gate);
//   3. they went to another page of the site, e.g. the "← Search" link
//      (Isna, 2026-09-27) -- kept instead of the browser's "leave site?".
// Those backups live in this browser (localStorage, one per account) --
// the backend has no draft concept -- and are offered back ("Continue
// writing" / "Discard it") the next time the journal is opened.
//
// Backend: /api/journal (SCRUM-81, backend/CONTRACTS.md "Trading journal").
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

  // createdAt / updatedAt / executedAt are real zoned instants
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

  // "BUY 0.0025 BTC @ $76,391.40 · 21 Sep, 10:14" -- the trade picker's text.
  function tradeText(t) {
    return t.side + " " + formatQuantity(t.quantity) + " BTC @ " +
      formatPrice(t.price) + " · " + formatWhen(t.executedAt);
  }

  // The same, labelled "Your trade" -- SCRUM-82: a linked trade must read
  // as what the person DID, not as a current price.
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
    const side = document.createElement("span");
    side.className = t.side === "BUY" ? "is-positive" : "is-negative";
    side.textContent = t.side;
    tag.appendChild(side);
    tag.appendChild(document.createTextNode(
      formatQuantity(t.quantity) + " BTC @ " + formatPrice(t.price) + " · " + formatWhen(t.executedAt)
    ));
    return tag;
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
      tradeId: mode === "new" ? formTradeId : null,
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
    return searchInput.value.trim() !== "" || dateFilter.value !== "";
  }

  function visibleEntries() {
    const q = searchInput.value.trim().toLowerCase();
    const day = dateFilter.value;
    return entries.filter(function (e) {
      if (q && String(e.body).toLowerCase().indexOf(q) === -1) return false;
      if (day && localDateKey(e.createdAt) !== day) return false;
      return true;
    });
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
      const item = document.createElement("button");
      item.type = "button";
      item.className = "jr-item" + (e.id === highlightId ? " is-just-saved" : "");
      item.dataset.id = String(e.id);

      const name = document.createElement("span");
      name.className = "jr-item-name";
      name.textContent = entryName(e.body);
      item.appendChild(name);

      const meta = document.createElement("span");
      meta.className = "jr-item-meta";
      if (e.tradeId != null) meta.appendChild(badge("Trade linked"));
      const when = document.createElement("span");
      when.textContent = formatWhen(e.createdAt);
      meta.appendChild(when);
      // SCRUM-82 point 2: "edited" only when updatedAt is non-null.
      if (e.updatedAt) {
        const edited = document.createElement("span");
        edited.textContent = "· edited";
        meta.appendChild(edited);
      }
      item.appendChild(meta);

      li.appendChild(item);
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

  function rebuildTradeSelect() {
    tradeSelect.replaceChildren();
    const none = document.createElement("option");
    none.value = "";
    none.textContent = "No trade";
    tradeSelect.appendChild(none);
    trades.forEach(function (t) {
      const opt = document.createElement("option");
      opt.value = String(t.id);
      opt.textContent = tradeText(t);
      tradeSelect.appendChild(opt);
    });
    tradeSelect.value = formTradeId != null ? String(formTradeId) : "";
    if (tradeSelect.value === "" && formTradeId != null) formTradeId = null;
    // UC05 4a: no trades yet is normal -- the picker is simply absent.
    tradeField.hidden = trades.length === 0;
  }

  function isDirty() {
    if (mode !== "new" && mode !== "edit") return false;
    if (bodyInput.value !== baseline.body) return true;
    return mode === "new" && formTradeId !== baseline.tradeId;
  }

  function clearFormMessages() {
    hide(bodyError);
    hide(formError);
    hide(unsavedBar);
    hide(idleNote);
  }

  // `start` optionally pre-fills the editor (continuing a kept draft); the
  // baseline stays the EMPTY entry, so that text still counts as unsaved.
  function enterNew(start) {
    listNotice = null;
    mode = "new";
    currentId = null;
    editorBackedUp = false;
    formTradeId = start && start.tradeId != null && tradesById.has(start.tradeId) ? start.tradeId : null;
    baseline = { body: "", tradeId: null };
    bodyInput.value = start ? start.body : "";
    detailMeta.textContent = start ? "New entry · continued from your unsaved text" : "New entry";
    hide(formLinks);
    clearFormMessages();
    rebuildTradeSelect();
    render();
    bodyInput.focus({ preventScroll: true });
  }

  function enterEdit(id, startBody) {
    const e = findEntry(id);
    if (!e) return;
    listNotice = null;
    mode = "edit";
    currentId = id;
    editorBackedUp = false;
    formTradeId = null;
    baseline = { body: e.body, tradeId: null };
    bodyInput.value = typeof startBody === "string" ? startBody : e.body;
    detailMeta.textContent = typeof startBody === "string"
      ? "Editing · continued from your unsaved text"
      : "Editing";
    // Only the text is editable (PATCH changes the body only), so the trade
    // link is shown as it is.
    hide(tradeField);
    fillLinks(formLinks, e.tradeId);
    clearFormMessages();
    render();
    bodyInput.focus({ preventScroll: true });
  }

  function enterRead(id) {
    listNotice = null;
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
      const el = listEl.querySelector('.jr-item[data-id="' + changedId + '"]');
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
    // UC05 5a: empty and whitespace-only are blocked before sending.
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
        : await request("PATCH", "/api/journal/" + encodeURIComponent(editingId), { body: text });
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
    // Branch on `code`, never on `message` (SCRUM-82).
    const code = errorCode(result);
    if (code === "INVALID_BODY") {
      bodyError.textContent = "An entry needs some text.";
      show(bodyError);
      bodyInput.focus();
    } else if (code === "NOT_FOUND" && creating) {
      // The linked trade isn't available (any more): drop the link and let
      // them save without it.
      formTradeId = null;
      await loadTrades();
      rebuildTradeSelect();
      formError.textContent = "That trade isn't available any more, so the link has been removed. Save again to keep the text without it.";
      show(formError);
    } else if (code === "NOT_FOUND") {
      entryGone(editingId);
    } else {
      formError.textContent = "Something went wrong — nothing was saved. Try again.";
      show(formError);
    }
    return null;
  }

  async function deleteCurrent() {
    const id = currentId;
    deleteYes.disabled = true;
    let result;
    try {
      result = await request("DELETE", "/api/journal/" + encodeURIComponent(id));
    } catch (networkErr) {
      deleteYes.disabled = false;
      deleteText.textContent = "Couldn't reach the server — the entry was not deleted. Try again?";
      return;
    }
    deleteYes.disabled = false;

    if (result.status === 401) { handleSignedOut("session-expired"); return; }
    if (result.status === 204) {
      entries = entries.filter(function (e) { return e.id !== id; });
      hide(deleteConfirm);
      enterList();
      return;
    }
    if (errorCode(result) === "NOT_FOUND") {
      entryGone(id);
      return;
    }
    deleteText.textContent = "Something went wrong — the entry was not deleted. Try again?";
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
      enterEdit(d.entryId, d.body);
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

  listEl.addEventListener("click", function (event) {
    const item = event.target.closest(".jr-item");
    if (!item) return;
    enterRead(Number(item.dataset.id));
  });

  searchInput.addEventListener("input", renderList);
  dateFilter.addEventListener("change", renderList);

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

  // A trade was just placed (demo-trading.js) -- the picker should offer
  // it straight away.
  document.addEventListener("easytrading:tradeplaced", function () {
    if (!username) return;
    loadTrades().then(function () {
      if (mode === "new") rebuildTradeSelect();
    });
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
