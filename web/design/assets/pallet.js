// Shared behaviour for the static Pallet designs: icons, app shell, theme, menus, modals, tabs, toasts.
(function () {
    const ICONS = {
        home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h5v-6h4v6h5V9.5"/>',
        apps: '<path d="M12 3 3.5 7.5 12 12l8.5-4.5L12 3Z"/><path d="m3.5 12 8.5 4.5 8.5-4.5"/><path d="m3.5 16.5 8.5 4.5 8.5-4.5"/>',
        users: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20c.6-3.4 3.3-5.5 6.5-5.5s5.9 2.1 6.5 5.5"/><path d="M16 4.7a3.5 3.5 0 0 1 0 6.6"/><path d="M18.5 14.8c1.6.8 2.7 2.6 3 5.2"/>',
        teams: '<rect x="3" y="3" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="2"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="2"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="2"/>',
        github: '<path d="M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21"/>',
        settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
        bell: '<path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.9 1.9 0 0 0 3.4 0"/>',
        user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c.8-4 4-6.5 8-6.5s7.2 2.5 8 6.5"/>',
        logout: '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="m16 17 5-5-5-5"/><path d="M21 12H9"/>',
        plus: '<path d="M12 5v14M5 12h14"/>',
        check: '<path d="M20 6 9 17l-5-5"/>',
        "check-circle": '<circle cx="12" cy="12" r="9"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
        x: '<path d="M18 6 6 18M6 6l12 12"/>',
        copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
        "chevron-down": '<path d="m6 9 6 6 6-6"/>',
        "chevron-right": '<path d="m9 6 6 6-6 6"/>',
        selector: '<path d="m7 15 5 5 5-5"/><path d="m7 9 5-5 5 5"/>',
        menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
        sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
        moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
        alert: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
        info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-4M12 8h.01"/>',
        mail: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m3 7 9 6 9-6"/>',
        branch: '<circle cx="6" cy="6" r="2.5"/><circle cx="6" cy="18" r="2.5"/><circle cx="18" cy="7" r="2.5"/><path d="M6 8.5v7M18 9.5c0 5-6 3.5-11 7"/>',
        commit: '<circle cx="12" cy="12" r="3.5"/><path d="M3 12h5.5M15.5 12H21"/>',
        refresh: '<path d="M21 12a9 9 0 0 1-15.4 6.4L3 16"/><path d="M3 12a9 9 0 0 1 15.4-6.4L21 8"/><path d="M21 3v5h-5M3 21v-5h5"/>',
        trash: '<path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 1H8a2 2 0 0 1-2-1L5 6"/>',
        external: '<path d="M15 3h6v6M10 14 21 3M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/>',
        lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 1 1 8 0v4"/>',
        key: '<circle cx="7.5" cy="15.5" r="4.5"/><path d="m10.7 12.3 9.8-9.8M17 6l3 3M14.5 8.5l2 2"/>',
        monitor: '<rect x="2" y="4" width="20" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
        phone: '<rect x="6" y="2" width="12" height="20" rx="2.5"/><path d="M11 18h2"/>',
        play: '<path d="M7 4.5v15l12.5-7.5L7 4.5Z"/>',
        link: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
        unlink: '<path d="m18.8 13.3 1.7-1.8a5 5 0 0 0-7-7l-1.8 1.7M5.2 10.7l-1.7 1.8a5 5 0 0 0 7 7l1.8-1.7M8 2v3M2 8h3M16 22v-3M22 16h-3"/>',
        shield: '<path d="M12 22s8-3.5 8-10V5l-8-3-8 3v7c0 6.5 8 10 8 10Z"/>',
        more: '<circle cx="5" cy="12" r="1.3"/><circle cx="12" cy="12" r="1.3"/><circle cx="19" cy="12" r="1.3"/>',
        grid: '<rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/>',
        arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
        back: '<path d="M19 12H5M11 18l-6-6 6-6"/>',
        folder: '<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7Z"/>',
        clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
        globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
        search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
        building: '<rect x="4" y="3" width="16" height="18" rx="1.5"/><path d="M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3"/>',
        send: '<path d="M22 2 11 13"/><path d="M22 2 15 22l-4-9-9-4 20-7Z"/>',
        eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
        archive: '<rect x="2" y="3" width="20" height="5" rx="1"/><path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8M10 12h4"/>',
        cloud: '<path d="M17.5 19a4.5 4.5 0 1 0-1.3-8.8A7 7 0 1 0 6 18.3"/><path d="M6 19h11.5"/>',
    };

    function svg(name, extra) {
        const body = ICONS[name];
        if (!body) return "";
        return `<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"${extra || ""}>${body}</svg>`;
    }

    function hydrateIcons(root) {
        root.querySelectorAll("i[data-icon]").forEach((el) => {
            el.outerHTML = svg(el.dataset.icon);
        });
    }

    // Sample session shared by every signed-in screen.
    const ME = { name: "Amani Otieno", email: "amani@kilimalabs.co", initials: "AO" };
    const ORGS = [
        { name: "Kilima Labs", slug: "kilima-labs", kind: "Team", role: "Owner", initials: "KL" },
        { name: "Amani Otieno", slug: "amani-otieno", kind: "Personal", role: "Owner", initials: "AO" },
        { name: "Savanna Pay", slug: "savanna-pay", kind: "Team", role: "Developer", initials: "SP" },
    ];

    function sidebar(active) {
        const nav = (id, href, icon, label, count) =>
            `<a href="${href}" class="${active === id ? "is-active" : ""}"${active === id ? ' aria-current="page"' : ""}>${svg(icon)}<span>${label}</span>${count ? `<span class="count">${count}</span>` : ""}</a>`;
        const org = ORGS[0];
        const orgItems = ORGS.map(
            (o, i) => `<a href="overview.html"><span class="avatar avatar-sm avatar-org">${o.initials}</span><span style="flex:1">${o.name}<br><span class="tiny muted">${o.kind} · ${o.role}</span></span>${i === 0 ? svg("check", ' style="width:16px;height:16px;color:var(--blue-text)"') : ""}</a>`,
        ).join("");
        return `
            <a class="brand" href="overview.html"><img src="../assets/pallet-mark.svg" alt=""><span>Pallet</span></a>
            <div class="org-switch" data-menu>
                <button type="button" aria-haspopup="menu" aria-expanded="false" data-menu-button>
                    <span class="avatar avatar-org">${org.initials}</span>
                    <span style="flex:1;min-width:0"><span class="name">${org.name}</span><span class="meta">${org.kind} · you're the ${org.role.toLowerCase()}</span></span>
                    ${svg("selector", ' style="width:16px;height:16px;color:var(--faint)"')}
                </button>
                <div class="menu" role="menu" hidden>
                    <div class="menu-label">Your organizations</div>
                    ${orgItems}
                    <hr>
                    <button type="button" data-open="modal-new-org">${svg("plus", ' style="width:16px;height:16px"')}New organization</button>
                </div>
            </div>
            <nav class="nav" aria-label="Main">
                ${nav("overview", "overview.html", "home", "Overview")}
                ${nav("apps", "apps.html", "apps", "Apps", "4")}
                <div class="nav-label">Organization</div>
                ${nav("members", "members.html", "users", "Members", "6")}
                ${nav("teams", "teams.html", "teams", "Teams", "3")}
                ${nav("github", "github.html", "github", "GitHub")}
                ${nav("settings", "org-settings.html", "settings", "Settings")}
            </nav>
            <div class="sidebar-foot">
                <nav class="nav" aria-label="Account">
                    ${nav("notifications", "notifications.html", "bell", "Notifications", "3")}
                    ${nav("account", "account.html", "user", "Your account")}
                </nav>
            </div>`;
    }

    function topbar(crumbs) {
        const parts = crumbs
            .split(";")
            .map((c) => c.trim())
            .filter(Boolean)
            .map((c, i, all) => {
                const [label, href] = c.split("|");
                if (i === all.length - 1) return `<span class="here">${label}</span>`;
                return `<a href="${href}">${label}</a><span class="sep">/</span>`;
            })
            .join("");
        return `
            <button class="btn btn-ghost btn-icon menu-toggle" type="button" aria-label="Open navigation" data-nav-toggle>${svg("menu")}</button>
            <nav class="crumbs" aria-label="Breadcrumb">${parts}</nav>
            <div class="topbar-actions">
                <button class="btn btn-ghost btn-icon" type="button" aria-label="Switch theme" data-theme-toggle>${svg("moon")}</button>
                <a class="btn btn-ghost btn-icon bell" href="notifications.html" aria-label="Notifications, 3 unread">${svg("bell")}<span class="badge-count">3</span></a>
                <div style="position:relative" data-menu>
                    <button class="btn btn-ghost" type="button" style="padding:0 4px" aria-haspopup="menu" aria-expanded="false" data-menu-button>
                        <span class="avatar">${ME.initials}</span>
                    </button>
                    <div class="menu" role="menu" style="right:0;top:calc(100% + 6px)" hidden>
                        <div style="padding:8px 10px 10px"><div class="person-name">${ME.name}</div><div class="person-meta">${ME.email}</div></div>
                        <hr>
                        <a href="account.html">${svg("user", ' style="width:16px;height:16px"')}Your account</a>
                        <a href="account.html#security">${svg("lock", ' style="width:16px;height:16px"')}Password and sessions</a>
                        <hr>
                        <a href="login.html" class="danger">${svg("logout", ' style="width:16px;height:16px"')}Sign out</a>
                    </div>
                </div>
            </div>`;
    }

    const NEW_ORG_MODAL = `
        <div class="modal-backdrop" id="modal-new-org" hidden>
            <form class="modal" role="dialog" aria-modal="true" aria-labelledby="new-org-title" data-fake-submit="Organization created">
                <div class="modal-head">
                    <div><h2 id="new-org-title">New organization</h2><p>Organizations hold apps, members and teams. You'll be its owner.</p></div>
                    <button class="btn btn-ghost btn-icon btn-sm" type="button" aria-label="Close" data-close>${svg("x")}</button>
                </div>
                <div class="modal-body">
                    <div class="field"><label for="new-org-name">Name</label><input class="input" id="new-org-name" placeholder="Savanna Pay" autocomplete="off"></div>
                    <div class="field"><label for="new-org-slug">URL</label>
                        <div class="input-group"><span class="addon">pallet.dev/</span><input class="input mono" id="new-org-slug" placeholder="savanna-pay" autocomplete="off"></div>
                        <span class="hint">Lowercase letters, numbers and hyphens. You can't change it later.</span>
                    </div>
                </div>
                <div class="modal-foot"><button class="btn btn-secondary" type="button" data-close>Cancel</button><button class="btn btn-primary" type="submit">Create organization</button></div>
            </form>
        </div>`;

    function buildShell() {
        const app = document.querySelector(".app[data-nav]");
        if (!app) return;
        const side = app.querySelector("[data-shell=sidebar]");
        const top = app.querySelector("[data-shell=topbar]");
        if (side) {
            side.className = "sidebar";
            side.innerHTML = sidebar(app.dataset.nav);
        }
        if (top) {
            top.className = "topbar";
            top.innerHTML = topbar(top.dataset.crumbs || "");
        }
        document.body.insertAdjacentHTML("beforeend", NEW_ORG_MODAL);
    }

    function designNote() {
        if (document.body.dataset.noNote !== undefined) return;
        const home = document.body.dataset.index || "../index.html";
        const states = (document.body.dataset.states || "")
            .split(",")
            .filter(Boolean)
            .map((s) => {
                const [id, label] = s.split(":");
                return `<button type="button" data-set-state="${id}" aria-pressed="false">${label}</button>`;
            })
            .join("");
        document.body.insertAdjacentHTML(
            "beforeend",
            `<div class="design-note" aria-label="Design preview controls"><a href="${home}">${svg("grid")}All screens</a><button type="button" data-theme-toggle>${svg("moon")}<span>Theme</span></button>${states ? `<span class="design-sep"></span>${states}` : ""}</div>`,
        );
        if (document.body.dataset.states) setState(document.body.dataset.states.split(",")[0].split(":")[0]);
    }

    // Theme: explicit choice sets data-theme; no choice follows the OS.
    function currentTheme() {
        const set = document.documentElement.dataset.theme;
        if (set) return set;
        return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }

    function applyStoredTheme() {
        try {
            const t = localStorage.getItem("pallet-theme");
            if (t) document.documentElement.dataset.theme = t;
        } catch (e) {}
    }

    function syncThemeIcons() {
        const icon = currentTheme() === "dark" ? "sun" : "moon";
        document.querySelectorAll("[data-theme-toggle]").forEach((b) => {
            const s = b.querySelector("svg");
            if (s) s.outerHTML = svg(icon);
        });
    }

    function toast(message) {
        let region = document.querySelector(".toast-region");
        if (!region) {
            region = document.createElement("div");
            region.className = "toast-region";
            region.setAttribute("role", "status");
            document.body.appendChild(region);
        }
        const t = document.createElement("div");
        t.className = "toast";
        t.innerHTML = svg("check-circle") + `<span>${message}</span>`;
        region.appendChild(t);
        setTimeout(() => t.remove(), 3200);
    }

    function closeMenus(except) {
        document.querySelectorAll("[data-menu]").forEach((m) => {
            if (m === except) return;
            const menu = m.querySelector(".menu");
            const btn = m.querySelector("[data-menu-button]");
            if (menu) menu.hidden = true;
            if (btn) btn.setAttribute("aria-expanded", "false");
        });
    }

    // Screens with several states list them in <body data-states="a:Label,b:Label">; blocks carry data-state="a".
    function setState(name) {
        document.body.dataset.state = name;
        document.querySelectorAll("[data-state]").forEach((el) => {
            el.hidden = !el.dataset.state.split(" ").includes(name);
        });
        document.querySelectorAll(".design-note [data-set-state]").forEach((b) => {
            b.setAttribute("aria-pressed", String(b.dataset.setState === name));
        });
    }

    function openModal(id) {
        const m = document.getElementById(id);
        if (!m) return;
        m.hidden = false;
        const first = m.querySelector("input, select, textarea, button:not([data-close])");
        if (first) first.focus();
    }

    function wire() {
        document.addEventListener("click", (e) => {
            const t = e.target;

            const themeBtn = t.closest("[data-theme-toggle]");
            if (themeBtn) {
                const next = currentTheme() === "dark" ? "light" : "dark";
                document.documentElement.dataset.theme = next;
                try {
                    localStorage.setItem("pallet-theme", next);
                } catch (err) {}
                syncThemeIcons();
                return;
            }

            const navToggle = t.closest("[data-nav-toggle]");
            const app = document.querySelector(".app");
            if (navToggle && app) {
                app.classList.toggle("nav-open");
                return;
            }
            if (app && app.classList.contains("nav-open") && !t.closest(".sidebar")) {
                app.classList.remove("nav-open");
            }

            const menuBtn = t.closest("[data-menu-button]");
            if (menuBtn) {
                const wrap = menuBtn.closest("[data-menu]");
                const menu = wrap.querySelector(".menu");
                const willOpen = menu.hidden;
                closeMenus(wrap);
                menu.hidden = !willOpen;
                menuBtn.setAttribute("aria-expanded", String(willOpen));
                return;
            }
            if (!t.closest(".menu")) closeMenus();

            const opener = t.closest("[data-open]");
            if (opener) {
                e.preventDefault();
                closeMenus();
                openModal(opener.dataset.open);
                return;
            }

            if (t.closest("[data-close]") || t.classList.contains("modal-backdrop")) {
                const m = t.closest(".modal-backdrop");
                if (m) m.hidden = true;
                return;
            }

            const copy = t.closest("[data-copy]");
            if (copy) {
                const text = copy.dataset.copy;
                const done = () => toast("Copied to clipboard");
                if (navigator.clipboard) navigator.clipboard.writeText(text).then(done, done);
                else done();
                return;
            }

            const toaster = t.closest("[data-toast]");
            if (toaster) {
                e.preventDefault();
                closeMenus();
                toast(toaster.dataset.toast);
                return;
            }

            const tab = t.closest("[role=tab]");
            if (tab) {
                const list = tab.closest("[role=tablist]");
                list.querySelectorAll("[role=tab]").forEach((x) => {
                    const on = x === tab;
                    x.setAttribute("aria-selected", String(on));
                    const panel = document.getElementById(x.getAttribute("aria-controls"));
                    if (panel) panel.hidden = !on;
                });
                return;
            }

            const stateBtn = t.closest("[data-set-state]");
            if (stateBtn) {
                e.preventDefault();
                setState(stateBtn.dataset.setState);
            }

            const seg = t.closest(".segmented button");
            if (seg) {
                seg.parentElement.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b === seg)));
            }
        });

        document.addEventListener("keydown", (e) => {
            if (e.key !== "Escape") return;
            closeMenus();
            document.querySelectorAll(".modal-backdrop:not([hidden])").forEach((m) => (m.hidden = true));
            const app = document.querySelector(".app");
            if (app) app.classList.remove("nav-open");
        });

        document.addEventListener("submit", (e) => {
            const form = e.target.closest("[data-fake-submit]");
            if (!form) return;
            e.preventDefault();
            const m = form.closest(".modal-backdrop");
            if (m) m.hidden = true;
            if (form.dataset.next) {
                location.href = form.dataset.next;
                return;
            }
            toast(form.dataset.fakeSubmit);
        });

        matchMedia("(prefers-color-scheme: dark)").addEventListener("change", syncThemeIcons);
    }

    applyStoredTheme();
    document.addEventListener("DOMContentLoaded", () => {
        buildShell();
        designNote();
        hydrateIcons(document);
        syncThemeIcons();
        wire();
        if (location.hash) {
            const hash = location.hash.slice(1);
            const target = document.querySelector(`[data-hash="${hash}"]`);
            if (target) target.click();
            else if (document.querySelector(`.design-note [data-set-state="${hash}"]`)) setState(hash);
        }
    });

    window.Pallet = { toast, svg, openModal };
})();
