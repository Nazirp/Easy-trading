// SCRUM-68 — log in, sign up, log out, and the logged-in state of the page.
//
// Talks to the auth half of the backend contract (backend/CONTRACTS.md):
//   POST /api/signup   {username, password} -> 201 {username, cashBalance}
//   POST /api/login    {username, password} -> 200 {username, cashBalance}
//   POST /api/logout                        -> 204
//   GET  /api/me                            -> 200 {username, cashBalance} | 401
//
// Three things worth knowing before changing anything here:
//
//  1. Credentials go in the POST body, never in a URL. A query string is
//     written to the server log, kept in browser history and sent on in the
//     Referer header.
//
//  2. The logged-in state is never stored on this side. No localStorage, no
//     variable that outlives a reload: on every page load the page asks
//     /api/me and renders the answer. Anything we cached here would be a
//     second copy of a fact only the server can actually settle, and it would
//     be wrong the moment the session expires. The session cookie is HttpOnly,
//     so this file could not read it even if it wanted to — that is the point.
//
//  3. Nothing here sends the cookie explicitly. The backend serves this page
//     from the same origin as the API, so fetch attaches it automatically —
//     that is the whole reason for the one-origin decision in CONTRACTS.md §0.
//     If the frontend ever moves to its own server, every fetch below needs
//     credentials: "include" and the backend needs CORS.
//
// Separate file from search.js on purpose: auth does not touch the chart and
// the chart does not touch auth. The one seam between them is the
// "easytrading:authchange" event dispatched below — the watchlist (SCRUM-22)
// is what will listen to it.

(function () {
  "use strict";

  // Same wording as search.js's fallback: anything that isn't a structured
  // error from the backend (network down, 500, unparseable body) gets one
  // plain sentence rather than a raw error.
  const GENERIC_FAILURE = "Something went wrong — try again in a moment.";

  const MODES = {
    login: {
      title: "Log in",
      subtitle: "Welcome back.",
      submit: "Log in",
      endpoint: "/api/login",
      passwordAutocomplete: "current-password",
      switchText: "No account yet?",
      switchAction: "Sign up",
      switchTo: "signup"
    },
    signup: {
      title: "Sign up",
      subtitle: "You start with $10,000 in virtual funds to practise with.",
      submit: "Create account",
      endpoint: "/api/signup",
      passwordAutocomplete: "new-password",
      switchText: "Already have an account?",
      switchAction: "Log in",
      switchTo: "login"
    }
  };

  const accountBar = document.getElementById("account-bar");
  const accountPrompt = document.getElementById("account-prompt");
  const watchlistStrip = document.getElementById("watchlist-strip");
  const accountControls = document.getElementById("account-controls");
  const accountUsername = document.getElementById("account-username");
  const logoutButton = document.getElementById("logout-button");

  const dialog = document.getElementById("auth-dialog");
  const form = document.getElementById("auth-form");
  const titleEl = document.getElementById("auth-title");
  const subtitleEl = document.getElementById("auth-subtitle");
  const usernameInput = document.getElementById("auth-username");
  const passwordInput = document.getElementById("auth-password");
  const errorEl = document.getElementById("auth-error");
  const submitButton = document.getElementById("auth-submit");
  const cancelButton = document.getElementById("auth-cancel");
  const switchTextEl = document.getElementById("auth-switch-text");
  const switchButton = document.getElementById("auth-switch");

  let currentMode = "login";
  let submitting = false;

  function hide(el) { el.hidden = true; }
  function show(el) { el.hidden = false; }

  function showError(message) {
    errorEl.textContent = message;
    show(errorEl);
  }

  function clearError() {
    errorEl.textContent = "";
    hide(errorEl);
  }

  // ---- Rendering the two states ----------------------------------------

  // user === null means "nobody is logged in", which is a normal state and
  // not an error. Called once on load and again after every login/logout, so
  // there is exactly one place that decides what the page looks like.
  function render(user) {
    show(accountBar);

    if (user) {
      accountUsername.textContent = user.username;
      show(accountControls);
      hide(accountPrompt);
      show(watchlistStrip);
    } else {
      accountUsername.textContent = "";
      hide(accountControls);
      show(accountPrompt);
      hide(watchlistStrip);
    }

    // The seam for SCRUM-22 (and anything else that cares): the watchlist
    // needs to load its items on login and clear them on logout, and this is
    // how it will be told, without auth.js having to know it exists.
    document.dispatchEvent(new CustomEvent("easytrading:authchange", {
      detail: { user: user }
    }));
  }

  async function refresh() {
    let response;
    try {
      response = await fetch("/api/me");
    } catch (networkErr) {
      // The API is unreachable. Render logged-out — it is the state in which
      // the page still works (search and the chart are public), and the login
      // attempt that follows will report the real problem.
      render(null);
      return;
    }

    if (response.status === 401) {
      render(null);          // nobody logged in: the expected answer, not an error
      return;
    }

    if (!response.ok) {
      render(null);
      return;
    }

    try {
      render(await response.json());
    } catch (parseErr) {
      render(null);
    }
  }

  // ---- The dialog -------------------------------------------------------

  function applyMode(mode) {
    const config = MODES[mode];
    currentMode = mode;

    titleEl.textContent = config.title;
    subtitleEl.textContent = config.subtitle;
    submitButton.textContent = config.submit;
    passwordInput.setAttribute("autocomplete", config.passwordAutocomplete);
    switchTextEl.textContent = config.switchText;
    switchButton.textContent = config.switchAction;
    clearError();
  }

  function openDialog(mode) {
    applyMode(mode);
    form.reset();
    // reset() wipes the autocomplete hint set above, so re-apply it.
    passwordInput.setAttribute("autocomplete", MODES[mode].passwordAutocomplete);
    clearError();
    dialog.showModal();
    usernameInput.focus();
  }

  function closeDialog() {
    if (dialog.open) {
      dialog.close();
    }
  }

  document.querySelectorAll("[data-auth-open]").forEach(function (button) {
    button.addEventListener("click", function () {
      openDialog(button.getAttribute("data-auth-open"));
    });
  });

  switchButton.addEventListener("click", function () {
    // Keep whatever has already been typed — switching from "log in" to
    // "sign up" after a failed login usually means the same username.
    const typedUsername = usernameInput.value;
    applyMode(MODES[currentMode].switchTo);
    usernameInput.value = typedUsername;
    passwordInput.value = "";
    usernameInput.focus();
  });

  cancelButton.addEventListener("click", closeDialog);

  // Clicking the backdrop closes it. The <dialog> element itself fills the
  // whole viewport including the backdrop, so a click landing on the dialog
  // rather than on the form inside it is a backdrop click.
  dialog.addEventListener("click", function (event) {
    if (event.target === dialog) {
      closeDialog();
    }
  });

  // ---- Submitting -------------------------------------------------------

  function setSubmitting(isSubmitting) {
    submitting = isSubmitting;
    submitButton.disabled = isSubmitting;
    cancelButton.disabled = isSubmitting;
    submitButton.textContent = isSubmitting ? "Please wait…" : MODES[currentMode].submit;
  }

  form.addEventListener("submit", async function (event) {
    event.preventDefault();
    if (submitting) return; // double-click, or Enter while the first request is in flight

    const username = usernameInput.value.trim();
    const password = passwordInput.value;

    // Checked here rather than left to the server: a blank field is the one
    // error we can answer instantly, and spaces-only would otherwise travel
    // all the way to the backend to come back as the same complaint. The
    // backend still enforces its own rules — this is a courtesy, not the
    // guarantee.
    if (username === "") {
      showError("Please enter a username.");
      usernameInput.focus();
      return;
    }
    if (password.trim() === "") {
      showError("Please enter a password.");
      passwordInput.focus();
      return;
    }

    clearError();
    setSubmitting(true);

    let response;
    try {
      response = await fetch(MODES[currentMode].endpoint, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: username, password: password })
      });
    } catch (networkErr) {
      setSubmitting(false);
      showError(GENERIC_FAILURE);
      return;
    }

    let body = null;
    try {
      body = await response.json();
    } catch (parseErr) {
      body = null; // a 4xx with no readable body still gets a message below
    }

    setSubmitting(false);

    if (response.ok) {
      closeDialog();
      render(body);
      return;
    }

    // The backend's own wording is used as-is for every structured error.
    // That matters most for 401: the message is deliberately identical for
    // "no such user" and "wrong password" so the form cannot be used to find
    // out which usernames exist, and rewording it here per status code would
    // give exactly that away.
    if (body && body.message) {
      showError(body.message);
    } else if (response.status === 409) {
      showError("That username is already taken.");
    } else if (response.status === 401) {
      showError("Username or password is incorrect.");
    } else {
      showError(GENERIC_FAILURE);
    }
    passwordInput.focus();
  });

  // ---- Logging out ------------------------------------------------------

  logoutButton.addEventListener("click", async function () {
    logoutButton.disabled = true;
    try {
      await fetch("/api/logout", { method: "POST" });
    } catch (networkErr) {
      // Ignored on purpose. /api/logout answers 204 whether or not anyone was
      // logged in, and if the request never arrived the session is still
      // alive on the server — which refresh() below will discover and show
      // honestly, rather than this page pretending to be logged out.
    }
    logoutButton.disabled = false;
    await refresh();
  });

  // ---- On load ----------------------------------------------------------
  // One question to the server, one render. Note this does not block
  // search.js: the chart is public, so it loads in parallel and does not care
  // whether anyone is signed in.
  refresh();
})();
