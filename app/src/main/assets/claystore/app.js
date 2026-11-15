// ClayStore — GitHub Release থেকে অ্যাপ, Android অ্যাপের (window.ClayApp) ব্রিজ থেকে ইউজার/ইনস্টল তথ্য
const OWNER = "Mosiveer379000";
const ADMIN_EMAIL = "masudd16@gmail.com";
const SB_URL = "https://fnrixuetjyhsacukahna.supabase.co";   // FamilyBook-এর Supabase URL (SupabaseRest.BASE)
const SB_KEY = "sb_publishable_P7lNqs7iNFe_GzxBxaqwDg_qxVl4mXb";   // publishable key (SupabaseRest.KEY)
// APK ফাইলের নাম -> Android package। repo-র নাম যাই হোক, Release-এর FamilyBook.apk দেখেই অ্যাপ চেনা হয়
// (নতুন অ্যাপ যোগ করতে এখানে একটা লাইন বাড়ালেই হয়)
const PKG_BY_APK = { "FamilyBook.apk": "com.familybook.app" };
const apkName = a => String((a && a.apk_url) || "").split("/").pop();
const isFamilyBook = a => apkName(a) === "FamilyBook.apk";
const REFRESH_MS = 5 * 60 * 1000;

const $ = id => document.getElementById(id);
const B = window.ClayApp || {};
const call = (f, ...a) => { try { return B[f] ? B[f](...a) : ""; } catch (e) { return ""; } };
let me = {}; try { me = JSON.parse(call("user") || "{}"); } catch (e) { }
const isAdmin = (me.email || "").toLowerCase() === ADMIN_EMAIL;

let apps = [], category = "All", term = "", cur = null, dev = null, myStars = 0, state = {};
const cap = s => s.charAt(0).toUpperCase() + s.slice(1);
const pretty = s => s.replace(/[-_]+apk$/i, "").replace(/[-_]+/g, " ").split(" ").map(cap).join(" ");
const safeUrl = u => (typeof u === "string" && u.startsWith("https://github.com/")) ? u : "#";
const safeIcon = u => (typeof u === "string" && u.startsWith("https://raw.githubusercontent.com/")) ? u : "";
const pkgOf = a => PKG_BY_APK[apkName(a)] || "";
const devName = () => (dev && dev.dev_name) || "Family LAB";
const norm = v => String(v || "").replace(/^v/i, "");

// ---- GitHub (ETag ক্যাশ) ----
async function gh(url) {
  const key = "clay:" + url; let c = null;
  try { c = JSON.parse(localStorage.getItem(key) || "null"); } catch (e) { }
  const r = await fetch(url, { headers: c ? { "If-None-Match": c.etag } : {} });
  if (r.status === 304) return c.data;
  if (!r.ok) { if (c) return c.data; throw new Error(r.status); }
  const data = await r.json(), etag = r.headers.get("ETag");
  if (etag) try { localStorage.setItem(key, JSON.stringify({ etag, data })); } catch (e) { }
  return data;
}
// ---- Supabase REST (ইউজারের টোকেনসহ) ----
async function sb(path, method = "GET", body, count) {
  const h = { apikey: SB_KEY, Authorization: "Bearer " + (call("token") || SB_KEY), "Content-Type": "application/json" };
  if (method !== "GET") h.Prefer = "return=representation,resolution=merge-duplicates";
  if (count) h.Prefer = "count=exact";
  const r = await fetch(SB_URL + "/rest/v1/" + path, { method, headers: h, body: body ? JSON.stringify(body) : undefined });
  if (!r.ok) throw new Error(r.status);
  if (count) return +(r.headers.get("content-range") || "0/0").split("/")[1] || 0;
  return r.status === 204 ? null : r.json();
}

const iconCache = {};
function iconNode(a) {
  const d = document.createElement("div"); d.className = "ic";
  const p = pkgOf(a); if (p && iconCache[p] === undefined) iconCache[p] = call("installedIcon", p) || "";
  const local = p ? iconCache[p] : "";   // ফোনে ইনস্টল থাকা অ্যাপের নিজের আইকন আগে; নইলে GitHub-এর icon.png
  const src = local || safeIcon(a.icon), letter = () => d.textContent = (a.name || "?")[0].toUpperCase();
  if (!src) { letter(); return d; }
  const im = new Image(); im.onerror = letter; im.src = src; d.append(im); return d;
}

function toast(t) {
  let e = $("toast");
  if (!e) { e = document.createElement("div"); e.id = "toast"; e.style.cssText = "position:fixed;left:50%;bottom:90px;transform:translateX(-50%);background:#2a2a2a;color:#fff;padding:7px 16px;border-radius:16px;font-size:12px;z-index:9"; document.body.append(e); }
  e.textContent = t; e.hidden = false; clearTimeout(e._t); e._t = setTimeout(() => e.hidden = true, 1800);
}

// ---- ইনস্টল অবস্থা ----
function status(a) {
  const st = state[pkgOf(a)];
  if (st === "installing" || st === "updating") return st;
  const v = pkgOf(a) ? call("installedVersion", pkgOf(a)) : "";
  if (!v) return "none";
  // versionName/tag মেলানো লাগে না: Release প্রকাশের পর ফোনে ইনস্টল না হয়ে থাকলে "update"
  const t = Number(call("installedTime", pkgOf(a))) || 0, rel = Date.parse(a.updated_at) || 0;
  return rel - t > 5 * 60 * 1000 ? "update" : "installed";
}
window.onClayState = (pkg, s) => { state[pkg] = s; if (s === "done") delete state[pkg]; render(); if (cur) openPage(cur); };

// ---- হোম ----
function render() {
  const cats = ["All", ...new Set(apps.map(a => a.category))];
  $("chips").replaceChildren(...cats.map(c => {
    const b = document.createElement("button"); b.className = "chip" + (c === category ? " on" : "");
    b.textContent = c; b.onclick = () => { category = c; render(); }; return b;
  }));
  const list = apps.filter(a => (category === "All" || a.category === category) &&
    (a.name + " " + a.description).toLowerCase().includes(term));
  $("msg").textContent = list.length ? "" : "No apps found.";
  $("list").replaceChildren(...list.map(a => {
    const row = document.createElement("div"); row.className = "app";
    const s = status(a), info = document.createElement("div"); info.className = "info";
    info.innerHTML = `<b></b><span class="dev"></span>`;
    info.firstChild.textContent = a.name;
    const v = document.createElement("i"); v.textContent = a.version_name; info.firstChild.append(v);
    info.lastChild.textContent = "Dev : " + devName();
    const btn = document.createElement("button");
    btn.className = "btn" + (s === "update" ? " up" : "");
    btn.textContent = s === "update" ? "Update" : s === "installed" ? "Open" : s === "none" ? "Install" : "…";
    btn.onclick = e => { e.stopPropagation(); act(a, s === "installed" ? "open" : "install"); };
    row.append(iconNode(a), info, btn); row.onclick = () => openPage(a); return row;
  }));
  $("avatarBtn").style.backgroundImage = me.avatar ? `url("${me.avatar}")` : "";
}

function act(a, what) {
  const p = pkgOf(a);
  if (what === "open") return call("open", p);
  if (what === "uninstall") return call("uninstall", p);
  if (!p) { location.href = safeUrl(a.apk_url); return; }
  state[p] = status(a) === "update" ? "updating" : "installing"; render(); cur && openPage(cur);
  call("install", a.apk_url, p, a.repo);
}

// ---- অ্যাপ পেজ ----
async function openPage(a) {
  cur = a; $("home").hidden = true; $("page").hidden = false;
  const s = status(a), busy = s === "installing" || s === "updating";
  $("pIcon").replaceChildren(iconNode(a).firstChild || ""); $("pIcon").className = "ring" + (busy ? " busy" : "");
  $("pName").textContent = a.name; $("pVer").textContent = a.version_name; $("pDev").textContent = devName();
  const mk = (t, c, f) => { const b = document.createElement("button"); b.className = "pb " + c; b.textContent = t; b.onclick = f; return b; };
  const btns = [];
  if (busy) btns.push(mk(s === "updating" ? "Updating..." : "Installing...", "", () => { }));
  else if (s === "none") btns.push(mk("Install now", "", () => act(a, "install")));
  else if (s === "update") btns.push(mk("Update now", "upd", () => act(a, "install")));
  if (s === "installed" || s === "update") {
    if (s === "installed") btns.push(mk("Open", "open", () => act(a, "open")));
    if (!isFamilyBook(a)) btns.push(mk("Uninstall", "un", () => act(a, "uninstall")));   // FamilyBook-এ Uninstall নেই
  }
  $("pBtns").replaceChildren(...btns);
  // FamilyBook পেজে Family Chat সাজেশন (এখন ডেমো: চাপলে "Coming soon"; পরে আসল অ্যাপ যোগ হলে এখানেই সংযোগ হবে)
  $("sugg").replaceChildren();
  if (isFamilyBook(a)) {
    const box = document.createElement("div"); box.className = "sg";
    box.innerHTML = `<h4>FamilyBook Messaging App Available. You Can Download Messenger Also</h4>`;
    const row = document.createElement("div"); row.className = "app"; row.style.padding = "4px 0";
    const i = document.createElement("div"); i.className = "info"; i.innerHTML = "<b></b>"; i.firstChild.textContent = "Family Chat";
    row.append(iconNode({ name: "Family Chat" }), i); row.onclick = () => toast("Coming soon");
    box.append(row); $("sugg").append(box);
  }
  loadStats(a);
}

async function loadStats(a) {
  const k = encodeURIComponent(a.slug);
  $("stDl").textContent = "…"; $("stRv").textContent = "Reviews (…)"; $("stRt").textContent = "Rated : …";
  try {
    const [dl, rv] = await Promise.all([
      sb(`clay_installs?app=eq.${k}`, "GET", null, true),
      sb(`clay_reviews?app=eq.${k}&select=user_id,name,avatar,stars,body,created_at&order=created_at.desc&limit=50`)
    ]);
    if (!cur || cur.slug !== a.slug) return;
    const mine = rv.find(r => r.user_id === me.uid);
    myStars = mine && mine.stars ? mine.stars : 0; drawStars(); $("rvText").value = mine && mine.body ? mine.body : "";
    // FamilyBook-এর বর্তমান নাম (profiles.name), না পেলে সেভ করা নাম
    try {
      const ids = [...new Set(rv.map(r => r.user_id))];
      if (ids.length) (await sb(`profiles?id=in.(${ids.join(",")})&select=id,name`)).forEach(p => { rv.forEach(r => { if (r.user_id === p.id && p.name) r.name = p.name; }); });
    } catch (e) { }
    const rated = rv.filter(r => r.stars), avg = rated.length ? (rated.reduce((s, r) => s + r.stars, 0) / rated.length).toFixed(1) : "–";
    $("stDl").textContent = (dl >= 1000 ? "+" + Math.floor(dl / 1000) * 1000 : dl >= 100 ? "+" + Math.floor(dl / 100) * 100 : dl >= 10 ? "+" + Math.floor(dl / 10) * 10 : dl) + " downloads";
    $("stRv").textContent = `Reviews (${rv.filter(r => r.body).length})`; $("stRt").textContent = "Rated : " + avg;
    $("reviews").replaceChildren(...rv.filter(r => r.body).map(r => {
      const d = document.createElement("div"); d.className = "rv";
      d.innerHTML = `<div class="h"><i></i><b></b><em></em></div><p></p>`;
      if (r.avatar) d.querySelector("i").style.backgroundImage = `url("${r.avatar}")`;
      d.querySelector("b").textContent = r.name || "User"; d.querySelector("em").textContent = "★".repeat(r.stars || 0);
      d.querySelector("p").textContent = r.body; return d;
    }));
  } catch (e) { $("stDl").textContent = "0 downloads"; $("stRv").textContent = "Reviews (0)"; $("stRt").textContent = "Rated : –"; }
}

function drawStars() {
  $("stars").replaceChildren(...[1, 2, 3, 4, 5].map(n => {
    const s = document.createElement("span"); s.textContent = "★"; s.className = n <= myStars ? "on" : "";
    s.onclick = async () => {
      myStars = n; drawStars();
      if (!cur || !me.uid) return;
      try { await sb("clay_reviews?on_conflict=app,user_id", "POST", { app: cur.slug, user_id: me.uid, name: me.name, avatar: me.avatarSmall || null, stars: n }); loadStats(cur); }
      catch (e) { alert("Could not save rating."); }
    }; return s;
  }));
}
$("rvSend").onclick = async () => {
  const body = $("rvText").value.trim();
  if (!cur || !body) return;
  try {
    const row = { app: cur.slug, user_id: me.uid, name: me.name, avatar: me.avatarSmall || null, body };
    if (myStars) row.stars = myStars;
    await sb("clay_reviews?on_conflict=app,user_id", "POST", row);
    $("rvText").value = ""; loadStats(cur);
  } catch (e) { alert("Could not post. Try again."); }
};
$("back").onclick = () => { cur = null; $("page").hidden = true; $("home").hidden = false; };

// ---- প্রোফাইল / ডেভেলপার মেনু ----
async function openMe() {
  $("home").hidden = true; $("page").hidden = true; $("me").hidden = false;
  if (isAdmin && dev === null) { try { dev = (await sb(`clay_developers?user_id=eq.${me.uid}&select=*`))[0] || false; } catch (e) { dev = false; } }
  const b = $("meBody"), pic = `<div class="pic" style="background-image:url('${me.avatar || ""}')"></div><h1></h1>`;
  const out = `<button class="out" id="lo">LogOut.</button>`;
  b.innerHTML = pic;
  b.querySelector("h1").textContent = me.name || "User";
  if (!isAdmin) { b.insertAdjacentHTML("beforeend", out); }
  else if (!dev) {
    b.insertAdjacentHTML("beforeend", `<p style="text-align:center">If you want to publish your own Android apk here you need to create a developer Account by clicking below.</p><button class="cy" id="cr">Create</button>` + out);
    $("cr").onclick = devForm;
  } else {
    const mine = apps.filter(a => a.repo.startsWith(OWNER + "/"));
    b.insertAdjacentHTML("beforeend", `<div class="kv"><span>Developer name :</span> <u></u></div><div class="kv"><span>Catagory name :</span> <u></u></div><div class="kv"><span>Total Published Apps :</span> <u></u></div>
      <div class="seg"><button class="on" data-t="pub">Published</button><button data-t="pen">Pending</button></div><div id="mineList"></div>` + out);
    const u = b.querySelectorAll("u"); u[0].textContent = dev.dev_name; u[1].textContent = dev.category; u[2].textContent = String(mine.length).padStart(2, "0");
    const showList = t => $("mineList").replaceChildren(...(t === "pub" ? mine : []).map(a => {
      const d = document.createElement("div"); d.className = "mine"; d.append(iconNode(a));
      const x = document.createElement("div"); x.innerHTML = "<b></b><small>Published</small>"; x.firstChild.textContent = a.name; d.append(x);
      d.onclick = () => { closeMe(); openPage(a); }; return d;
    }));
    b.querySelectorAll(".seg button").forEach(s => s.onclick = () => { b.querySelectorAll(".seg button").forEach(z => z.classList.remove("on")); s.classList.add("on"); showList(s.dataset.t); });
    showList("pub");
  }
  $("lo").onclick = () => call("logout");
}
function devForm() {
  const b = $("meBody"), cats = ["Educational", "Games", "Social", "Tools", "Entertainment", "Business", "Lifestyle"];
  b.innerHTML = `<div class="pic" style="background-image:url('${me.avatar || ""}')"></div><h1></h1><div class="bar">Developer Form :</div>
    <input class="field" id="dn" placeholder="Developer Name :"><select class="field" id="dc"><option value="">Select Categories :</option>${cats.map(c => `<option>${c}</option>`).join("")}</select>
    <div class="row2"><button class="c" id="cc">Cancel</button><button class="cy" id="cs" style="font-size:14px">Create</button></div>`;
  b.querySelector("h1").textContent = me.name || "";
  $("cc").onclick = openMe;
  $("cs").onclick = async () => {
    const n = $("dn").value.trim(), c = $("dc").value; if (!n || !c) return;
    try { dev = (await sb("clay_developers?on_conflict=user_id", "POST", { user_id: me.uid, dev_name: n, category: c }))[0]; openMe(); render(); }
    catch (e) { alert("Could not create. Try again."); }
  };
}
function closeMe() { $("me").hidden = true; $("home").hidden = false; }
$("meClose").onclick = closeMe; $("avatarBtn").onclick = openMe;

document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => {
  document.querySelectorAll(".tabs button").forEach(x => x.classList.remove("on")); b.classList.add("on");
  const t = b.dataset.tab; $("searchBox").hidden = t !== "search";
  if (t === "search") $("q").focus(); else if (t === "you") openMe(); else { category = t === "games" ? "Games" : "All"; render(); }
});
$("q").oninput = e => { term = e.target.value.trim().toLowerCase(); render(); };

function toApp(r, rel) {
  const a = (rel.assets || []).find(x => x.name.endsWith(".apk")); if (!a) return null;
  return { slug: r.name, name: pretty(r.name), repo: r.full_name, category: cap((r.topics || [])[0] || "apps"),
    version_name: rel.tag_name, size: a.size, description: r.description || "", apk_url: a.browser_download_url,
    updated_at: rel.published_at, icon: `https://raw.githubusercontent.com/${r.full_name}/HEAD/icon.png` };
}
async function load() {
  try {
    const repos = (await gh(`https://api.github.com/users/${OWNER}/repos?per_page=100&type=owner`)).filter(r => !r.fork);
    const rows = await Promise.all(repos.map(async r => { try { return toApp(r, await gh(`https://api.github.com/repos/${r.full_name}/releases/latest`)); } catch (e) { return null; } }));
    apps = rows.filter(Boolean).filter(a => pkgOf(a)).sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
    render();
    const h = decodeURIComponent(location.hash.slice(1)), hit = h && apps.find(x => x.slug === h);
    if (hit && !cur) openPage(hit);
  } catch (e) { $("msg").textContent = "Could not load. GitHub limit reached? Try again later."; }
}
(async () => { try { const d = await sb("clay_developers?select=*&limit=1"); dev = d[0] || false; } catch (e) { } render(); })();
drawStars(); load(); setInterval(load, REFRESH_MS);

// Android ব্যাক বাটন: ভেতরের পেজ থাকলে আগে সেটা বন্ধ, নইলে false (অ্যাক্টিভিটি বন্ধ হবে)
window.clayBack = () => {
  if (!$("me").hidden) { closeMe(); return true; }
  if (cur) { $("back").onclick(); return true; }
  return false;
};
