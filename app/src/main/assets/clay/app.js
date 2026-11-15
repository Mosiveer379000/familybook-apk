// কোনো সার্ভার/ডাটাবেস নেই: ব্রাউজার সরাসরি GitHub API থেকে সব public repo-র সর্বশেষ Release পড়ে।
const OWNER = "Mosiveer379000";   // তোমার GitHub username
const TOPIC = "";                 // খালি = সব repo; "clay-app" দিলে শুধু ওই topic-ওয়ালা repo দেখাবে
const REFRESH_MS = 5 * 60 * 1000; // কত সময় পরপর নতুন Release খুঁজবে

const $ = id => document.getElementById(id);
let apps = [], category = "All", term = "", opened = false;

const WEEK = 7 * 24 * 3600 * 1000;
const isNew = a => Date.now() - new Date(a.updated_at) < WEEK;
const mb = n => ((n || 0) / 1048576).toFixed(1) + " MB";
const cap = s => s.charAt(0).toUpperCase() + s.slice(1);
const pretty = s => s.replace(/[-_]+/g, " ").split(" ").map(cap).join(" ");
const safeUrl = u => (typeof u === "string" && u.startsWith("https://github.com/")) ? u : "#";
const safeIcon = u => (typeof u === "string" && u.startsWith("https://raw.githubusercontent.com/")) ? u : "";

// ETag দিয়ে ডাকলে বদল না হলে GitHub 304 দেয়, যা রেট-লিমিটে গণনা হয় না (ঘণ্টায় ৬০ রিকোয়েস্ট/আইপি)
async function gh(url) {
  const key = "clay:" + url;
  let c = null;
  try { c = JSON.parse(localStorage.getItem(key) || "null"); } catch (e) { }
  const r = await fetch(url, { headers: c ? { "If-None-Match": c.etag } : {} });
  if (r.status === 304) return c.data;
  if (!r.ok) { if (c) return c.data; throw new Error(r.status); }
  const data = await r.json(), etag = r.headers.get("ETag");
  if (etag) { try { localStorage.setItem(key, JSON.stringify({ etag, data })); } catch (e) { } }
  return data;
}

function iconNode(a) {
  const d = document.createElement("div");
  d.className = "ic";
  const letter = () => { d.textContent = (a.name || "?")[0].toUpperCase(); };
  const src = safeIcon(a.icon);
  if (!src) { letter(); return d; }
  const im = document.createElement("img");   // repo-র রুটে icon.png থাকলে সেটা দেখায়
  im.alt = "";
  im.onerror = letter;
  im.src = src;
  d.append(im);
  return d;
}

function render() {
  const cats = ["All", ...new Set(apps.map(a => a.category))];
  $("chips").replaceChildren(...cats.map(c => {
    const b = document.createElement("button");
    b.className = "chip" + (c === category ? " on" : "");
    b.textContent = c;
    b.onclick = () => { category = c; render(); };
    return b;
  }));

  const list = apps.filter(a =>
    (category === "All" || a.category === category) &&
    (a.name + " " + a.description).toLowerCase().includes(term));
  $("msg").textContent = list.length ? "" : "No apps found.";

  $("list").replaceChildren(...list.map(a => {
    const row = document.createElement("div");
    row.className = "app";
    const info = document.createElement("div");
    info.className = "info";
    const n = document.createElement("b");
    n.textContent = a.name;
    if (isNew(a)) { const t = document.createElement("small"); t.className = "new"; t.textContent = "NEW"; n.append(t); }
    const s1 = document.createElement("span");
    s1.textContent = a.description || a.repo;
    const s2 = document.createElement("span");
    s2.textContent = `${a.version_name} • ${mb(a.size)}`;
    info.append(n, s1, s2);
    const btn = document.createElement("a");
    btn.className = "btn";
    btn.textContent = "Install";
    btn.href = safeUrl(a.apk_url);
    btn.onclick = e => e.stopPropagation();
    row.append(iconNode(a), info, btn);
    row.onclick = () => openSheet(a);
    return row;
  }));
}

function openSheet(a) {
  $("sIcon").replaceChildren(iconNode(a));
  $("sName").textContent = a.name;
  $("sMeta").textContent = `${a.version_name} • ${mb(a.size)} • ${new Date(a.updated_at).toLocaleDateString()}`;
  $("sBtn").href = safeUrl(a.apk_url);
  $("sDesc").textContent = a.description || "—";
  $("sNew").textContent = a.whats_new || "—";
  $("sheet").hidden = false;
}
$("close").onclick = () => $("sheet").hidden = true;
$("sheet").onclick = e => { if (e.target === $("sheet")) $("sheet").hidden = true; };
$("q").oninput = e => { term = e.target.value.trim().toLowerCase(); render(); };

document.querySelectorAll(".tabs button").forEach(b => b.onclick = () => {
  document.querySelectorAll(".tabs button").forEach(x => x.classList.remove("on"));
  b.classList.add("on");
  if (b.dataset.tab === "search") $("q").focus();
  else { category = b.dataset.tab === "games" ? "Games" : "All"; render(); }
});

async function load() {
  try {
    const repos = (await gh(`https://api.github.com/users/${OWNER}/repos?per_page=100&type=owner`))
      .filter(r => !r.fork && (!TOPIC || (r.topics || []).includes(TOPIC)));
    const rows = await Promise.all(repos.map(async r => {
      try {
        const rel = await gh(`https://api.github.com/repos/${r.full_name}/releases/latest`);
        const a = (rel.assets || []).find(x => x.name.endsWith(".apk"));
        if (!a) return null;
        return {
          name: pretty(r.name), repo: r.full_name, category: cap((r.topics || [])[0] || "apps"),
          version_name: rel.tag_name, size: a.size, description: r.description || "",
          whats_new: (rel.body || "").trim().slice(0, 500), apk_url: a.browser_download_url,
          updated_at: rel.published_at,
          icon: `https://raw.githubusercontent.com/${r.full_name}/HEAD/icon.png`
        };
      } catch (e) { return null; }   // Release নেই (404)
    }));
    apps = rows.filter(Boolean).sort((a, b) => new Date(b.updated_at) - new Date(a.updated_at));
    render();
    // FamilyBook থেকে আসা লিংক (…/#familybook-apk): সরাসরি ওই অ্যাপের পেজ খোলে
    const h = decodeURIComponent(location.hash.slice(1));
    if (h && !opened) {
      const hit = apps.find(x => x.repo.split("/")[1] === h);
      if (hit) { openSheet(hit); opened = true; }
    }
  } catch (e) {
    $("msg").textContent = "Could not load. GitHub limit reached? Try again later.";
  }
}

load();
setInterval(load, REFRESH_MS);   // খোলা থাকলে নিজে নতুন ভার্সন খুঁজে তালিকা বদলায়
