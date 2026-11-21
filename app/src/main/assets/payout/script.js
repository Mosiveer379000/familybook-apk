// ==================== CONFIG ====================
const SB_URL = "https://fnrixuetjyhsacukahna.supabase.co";
const SB_KEY = "sb_publishable_P7lNqs7iNFe_GzxBxaqwDg_qxVl4mXb";
const PROFILE_TABLE = "profiles";           // মূল আইডির প্রোফাইল টেবিল
const MIN_PAYOUT = 50;

const DEFAULT_AVATAR =
  "data:image/svg+xml;utf8," +
  encodeURIComponent(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="50" fill="#eef1f5"/><circle cx="50" cy="38" r="17" fill="#c9ccd1"/><path d="M22 92 C24 68 76 68 78 92 Z" fill="#c9ccd1"/></svg>`);

// ==================== STATE (সব Supabase থেকে আসে, কোনো ডেমো নেই) ====================
let sb = null;
let uid = null;
let appState = {
  loggedInUser: { name: "", avatar: DEFAULT_AVATAR },
  balanceVisible: false,
  actualBalance: 0,     // available
  pendingPayout: 0,
  totalEarned: 0,
  totalWithdrawn: 0,
  approxEarning: 0,
  payoutMethod: null,
  taxInfo: null,
  accountInfo: null,
  ownerInfo: null,
  transactions: []
};

// ==================== NATIVE BRIDGE ====================
function getSession() {
  try {
    if (window.Android && Android.getSession) return JSON.parse(Android.getSession());
  } catch (e) {}
  return null;
}
// মূল প্রোফাইল (ProfileActivity — লগইন/রেজিস্ট্রেশনে তৈরি প্রোফাইল) খোলে
function openProfile() {
  if (window.Android && Android.openProfile) Android.openProfile();
  else showToast("প্রোফাইল শুধু অ্যাপ থেকে খোলা যায়");
}
// অ্যান্ড্রয়েড Back বাটন: true মানে ওয়েব পেজ নিজেই হ্যান্ডেল করেছে
window.nativeBack = function () {
  const open = document.querySelector(".modal-overlay.open");
  if (open) { open.classList.remove("open"); return true; }
  if (document.getElementById("view-payout-settings").classList.contains("active")) {
    closeSettingsView();
    return true;
  }
  return false;
};

// ==================== INIT ====================
document.addEventListener("DOMContentLoaded", async () => {
  try { appState.balanceVisible = localStorage.getItem("fb_bal_vis") === "1"; } catch (e) {}
  document.getElementById("header-avatar-1").src = DEFAULT_AVATAR;
  document.getElementById("header-avatar-2").src = DEFAULT_AVATAR;
  // Payout account admins: অ্যাডমিনের ছবি/নামে ক্লিক করলেও মূল প্রোফাইলে যাবে
  const adminCard = document.getElementById("admin-info-content");
  if (adminCard) {
    adminCard.style.cursor = "pointer";
    adminCard.addEventListener("click", openProfile);
  }
  renderAll();

  const s = getSession();
  if (!s || !s.token || !s.uid) {
    showToast("লগইন সেশন পাওয়া যায়নি, আবার লগইন করুন");
    return;
  }
  uid = s.uid;
  sb = supabase.createClient(SB_URL, SB_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
    // প্রতিবার অ্যাপ থেকে বৈধ টোকেন নেয় (মেয়াদ শেষ হলে অ্যাপ নিজেই রিফ্রেশ করে)
    accessToken: async () => { const x = getSession(); return x ? x.token : SB_KEY; },
    // পুরনো supabase-js ভার্সনে accessToken অপশন না থাকলেও যেন প্রতিটি রিকোয়েস্টে নতুন টোকেন যায়
    global: {
      fetch: (url, opts = {}) => {
        const x = getSession();
        const h = new Headers(opts.headers || {});
        if (x && x.token) h.set("Authorization", "Bearer " + x.token);
        return fetch(url, Object.assign({}, opts, { headers: h }));
      }
    }
  });
  try { sb.realtime.setAuth(s.token); } catch (e) {}

  await Promise.all([loadProfile(), loadSettings(), loadTransactions()]);
  subscribeRealtime();
});

function subscribeRealtime() {
  sb.channel("payout-" + uid)
    .on("postgres_changes", { event: "*", schema: "public", table: "payout_transactions", filter: "user_id=eq." + uid }, loadTransactions)
    .on("postgres_changes", { event: "*", schema: "public", table: "payout_settings", filter: "user_id=eq." + uid }, loadSettings)
    .on("postgres_changes", { event: "*", schema: "public", table: PROFILE_TABLE, filter: "id=eq." + uid }, loadProfile)
    .subscribe();
}

// ==================== LOADERS ====================
let lastAvatarKey = null;
async function loadProfile() {
  const { data } = await sb.from(PROFILE_TABLE).select("id,name,avatar_key").eq("id", uid).maybeSingle();
  if (!data) return;
  appState.loggedInUser.name = data.name || "";
  syncProfileAndAdminUI();
  // ছবি অ্যাপের Media থেকে আসে (avatar_key → বিটম্যাপ); কী বদলালেই শুধু নতুন করে আনে
  if (data.avatar_key !== lastAvatarKey) {
    lastAvatarKey = data.avatar_key;
    if (data.avatar_key && window.Android && Android.requestAvatar) {
      Android.requestAvatar(data.avatar_key);
    } else {
      appState.loggedInUser.avatar = DEFAULT_AVATAR;
      syncProfileAndAdminUI();
    }
  }
}
// অ্যাপ থেকে কল হয়
window.onAvatar = function (dataUrl) {
  appState.loggedInUser.avatar = dataUrl || DEFAULT_AVATAR;
  syncProfileAndAdminUI();
};

async function loadSettings() {
  const { data } = await sb.from("payout_settings").select("*").eq("user_id", uid).maybeSingle();
  appState.payoutMethod = (data && data.payout_method) || null;
  appState.taxInfo = (data && data.tax_info) || null;
  appState.accountInfo = (data && data.account_info) || null;
  appState.ownerInfo = (data && data.owner_info) || null;
  renderSettingsPage();
}

async function loadTransactions() {
  const { data, error } = await sb.from("payout_transactions")
    .select("*").eq("user_id", uid).order("created_at", { ascending: false });
  if (error) { showToast("ডেটা লোড করা যায়নি"); return; }

  appState.transactions = (data || []).map((t) => ({
    id: t.id,
    type: t.type,
    title: t.title,
    source: t.source || "",
    date: fmtDate(t.created_at),
    created: new Date(t.created_at),
    amount: Number(t.amount),
    status: t.status === "completed" ? "Completed" : t.status === "rejected" ? "Rejected" : "Pending"
  }));

  let earned = 0, completed = 0, pending = 0;
  appState.transactions.forEach((t) => {
    if (t.type === "earning" && t.status === "Completed") earned += t.amount;
    if (t.type === "payout" && t.status === "Completed") completed += t.amount;
    if (t.type === "payout" && t.status === "Pending") pending += t.amount;
  });
  appState.totalEarned = round2(earned);
  appState.totalWithdrawn = round2(completed);
  appState.pendingPayout = round2(pending);
  appState.actualBalance = round2(earned - completed - pending);
  renderAll();
}

function renderAll() {
  syncProfileAndAdminUI();
  updateBalanceUI();
  renderOverview();
  renderTransactions();
  renderSettingsPage();
}

// ==================== NAVIGATION & TABS ====================
function switchTab(tabName) {
  document.getElementById("tab-overview").classList.toggle("active", tabName === "overview");
  document.getElementById("tab-transactions").classList.toggle("active", tabName === "transactions");
  document.getElementById("content-overview").classList.toggle("active", tabName === "overview");
  document.getElementById("content-transactions").classList.toggle("active", tabName === "transactions");
  if (tabName === "transactions") renderTransactions();
}

function openSettingsView() {
  document.getElementById("view-payout-info").classList.remove("active");
  document.getElementById("view-payout-settings").classList.add("active");
  renderSettingsPage();
  window.scrollTo(0, 0);
}

function closeSettingsView() {
  document.getElementById("view-payout-settings").classList.remove("active");
  document.getElementById("view-payout-info").classList.add("active");
  window.scrollTo(0, 0);
}

function handleBackFromMain() {
  if (window.Android && Android.close) Android.close();
}

// ==================== BALANCE ====================
function toggleBalanceVisibility() {
  appState.balanceVisible = !appState.balanceVisible;
  try { localStorage.setItem("fb_bal_vis", appState.balanceVisible ? "1" : "0"); } catch (e) {}
  updateBalanceUI();
}

function updateBalanceUI() {
  const balEl = document.getElementById("total-balance-display");
  const eyeIcon = document.getElementById("eye-icon");
  if (appState.balanceVisible) {
    balEl.textContent = "$" + appState.actualBalance.toFixed(2);
    eyeIcon.innerHTML = `
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94"></path>
      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19"></path>
      <line x1="1" y1="1" x2="23" y2="23"></line>`;
  } else {
    balEl.textContent = "$--.--";
    eyeIcon.innerHTML = `
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"></path>
      <circle cx="12" cy="12" r="3"></circle>`;
  }
  document.getElementById("payout-pending-display").textContent = "$" + appState.pendingPayout.toFixed(2);
  document.getElementById("pending-asof").textContent = "as of " + fmtDate(new Date());
}

// ==================== OVERVIEW (চলতি মাসের আয়) ====================
const SRC_ICONS = {
  shorts: `<rect x="2" y="4" width="20" height="16" rx="3"></rect><line x1="2" y1="9" x2="22" y2="9"></line><line x1="7" y1="4" x2="10" y2="9"></line><line x1="14" y1="4" x2="17" y2="9"></line><polygon points="10 12 15 14.5 10 17 10 12" fill="currentColor"></polygon>`,
  photos: `<rect x="3" y="5" width="16" height="14" rx="2"></rect><path d="M21 8v11a2 2 0 0 1-2 2H6"></path><circle cx="8.5" cy="10" r="1.5"></circle><polyline points="21 15 15 10 5 18"></polyline>`,
  other: `<circle cx="12" cy="12" r="9"></circle><path d="M12 7v10M9.5 9.5c0-1 1-1.5 2.5-1.5s2.5.8 2.5 1.8-1 1.5-2.5 1.7-2.5.7-2.5 1.8 1 1.9 2.5 1.9 2.5-.6 2.5-1.6"></path>`
};

function renderOverview() {
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), 1);
  const end = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  const prevStart = new Date(now.getFullYear(), now.getMonth() - 1, 1);

  let cur = 0, prev = 0;
  const bySource = {};
  appState.transactions.forEach((t) => {
    if (t.type !== "earning" || t.status !== "Completed") return;
    if (t.created >= start) {
      cur += t.amount;
      bySource[t.source] = (bySource[t.source] || 0) + t.amount;
    } else if (t.created >= prevStart) {
      prev += t.amount;
    }
  });
  appState.approxEarning = round2(cur);
  document.getElementById("approx-earning-display").textContent = "$" + cur.toFixed(2);
  document.getElementById("date-range-title").textContent = fmtDate(start) + " - " + fmtDate(end);

  const g = document.getElementById("growth-indicator");
  if (prev > 0 && cur > 0) {
    const pct = Math.round(((cur - prev) / prev) * 100);
    g.innerHTML = `<span class="arrow-up">${pct >= 0 ? "↑" : "↓"}</span> ${Math.abs(pct)}% from previous month`;
    g.style.display = "";
  } else {
    g.style.display = "none";
  }

  const box = document.getElementById("earning-sources");
  const keys = Object.keys(bySource);
  box.innerHTML = keys.map((k) => {
    const icon = /short/i.test(k) ? SRC_ICONS.shorts : /photo/i.test(k) ? SRC_ICONS.photos : SRC_ICONS.other;
    return `
      <div class="earning-source-item">
        <div class="source-icon">
          <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${icon}</svg>
        </div>
        <div class="source-details">
          <div class="source-amount">$${bySource[k].toFixed(2)}</div>
          <div class="source-desc">From familyBook <span class="link-blue">${esc(k)}</span></div>
        </div>
      </div>`;
  }).join("");
}

// ==================== TRANSACTIONS PAGE ====================
function renderTransactions() {
  const filter = document.getElementById("tx-filter").value;
  const listEl = document.getElementById("transaction-list");

  document.getElementById("tx-total-earned").textContent = "$" + appState.totalEarned.toFixed(2);
  document.getElementById("tx-total-withdrawn").textContent = "$" + appState.totalWithdrawn.toFixed(2);
  document.getElementById("tx-available").textContent = "$" + appState.actualBalance.toFixed(2);

  const filtered = appState.transactions.filter((tx) => filter === "all" || tx.type === filter);

  if (filtered.length === 0) {
    listEl.innerHTML = `<div class="empty-setting-box"><span class="empty-setting-text">No transactions yet.</span></div>`;
    return;
  }

  listEl.innerHTML = filtered.map((tx) => {
    const isEarning = tx.type === "earning";
    const sign = isEarning ? "+" : "-";
    const amountClass = isEarning ? "text-green" : "text-blue";
    const statusClass = tx.status === "Completed" ? "status-completed" : "status-pending";
    const src = isEarning ? "familyBook " + tx.source : tx.source;
    return `
      <div class="tx-item">
        <div class="tx-item-left">
          <div class="tx-badge-icon ${tx.type}">${isEarning ? "↓" : "↑"}</div>
          <div>
            <div class="tx-title">${esc(tx.title)}</div>
            <div class="tx-meta">${esc(src)} • ${tx.date}</div>
          </div>
        </div>
        <div class="tx-item-right">
          <div class="tx-amount ${amountClass}">${sign}$${tx.amount.toFixed(2)}</div>
          <span class="tx-status ${statusClass}">${tx.status}</span>
        </div>
      </div>`;
  }).join("");
}

// ==================== RENDER PAYOUT SETTINGS ====================
// ==================== RENDER PAYOUT SETTINGS (IMAGES 2 & 3) ====================
function renderSettingsPage() {
  // 1. Payout Method
  const pmContainer = document.getElementById("payout-method-content");
  const pmActionBtn = document.getElementById("btn-payout-method-action");

  if (!appState.payoutMethod) {
    pmActionBtn.style.display = "inline-block";
    pmActionBtn.textContent = "+ Add";
    pmContainer.innerHTML = `
      <div class="empty-setting-box" onclick="openModal('modal-payout-method')">
        <div>
          <div class="empty-setting-text">Add Payout Method</div>
          <div class="empty-setting-sub">bKash, Nagad, Rocket, VISA, MasterCard</div>
        </div>
        <span class="link-blue" style="font-weight:700;">Add &rsaquo;</span>
      </div>
    `;
  } else {
    pmActionBtn.style.display = "none";
    const pm = appState.payoutMethod;
    const maskedNum = maskNumber(pm.accountNumber);
    pmContainer.innerHTML = `
      <div class="setting-row-clickable" onclick="openModal('modal-payout-method')">
        <div class="setting-row-left">
          <div class="circle-icon-bg">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <rect x="2" y="5" width="20" height="14" rx="2"></rect>
              <line x1="2" y1="10" x2="22" y2="10"></line>
            </svg>
          </div>
          <div>
            <div class="row-main-title">${esc(pm.holderName)}</div>
            <div class="row-sub-line">${esc(pm.type)}•••${maskedNum}</div>
            <div class="row-sub-line">${earningSourceCount()} earning sources</div>
          </div>
        </div>
        <div class="chevron-right">&rsaquo;</div>
      </div>
    `;
  }

  // 2. Tax Info (eTIN)
  const taxContainer = document.getElementById("tax-info-content");
  const taxActionBtn = document.getElementById("btn-tax-info-action");

  if (!appState.taxInfo) {
    taxActionBtn.style.display = "inline-block";
    taxActionBtn.textContent = "+ Add";
    taxContainer.innerHTML = `
      <div class="empty-setting-box" onclick="openModal('modal-tax-info')">
        <div>
          <div class="empty-setting-text">Add Tax Info (eTIN)</div>
          <div class="empty-setting-sub">Submit your eTIN number to verify tax status</div>
        </div>
        <span class="link-blue" style="font-weight:700;">Add &rsaquo;</span>
      </div>
    `;
  } else {
    taxActionBtn.style.display = "none";
    const tx = appState.taxInfo;
    taxContainer.innerHTML = `
      <div class="setting-row-clickable" onclick="openModal('modal-tax-info')">
        <div class="setting-row-left">
          <div class="circle-icon-bg">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
              <polyline points="14 2 14 8 20 8"></polyline>
              <line x1="16" y1="13" x2="8" y2="13"></line>
              <line x1="16" y1="17" x2="8" y2="17"></line>
            </svg>
          </div>
          <div>
            <div class="row-main-title">${esc(tx.formType)}</div>
            <div class="row-sub-line"><span class="verified-green">Verified</span> • Updated ${esc(tx.updatedDate)}</div>
            <div class="row-sub-line">eTIN: ********${maskNumber(tx.etin)} • ${earningSourceCount()} earning sources</div>
          </div>
        </div>
        <div class="chevron-right">&rsaquo;</div>
      </div>
    `;
  }

  // 3. Payout Account Info
  const accContainer = document.getElementById("account-info-content");
  const accAddBtn = document.getElementById("btn-account-info-add");

  if (!appState.accountInfo) {
    accAddBtn.style.display = "inline-block";
    accContainer.innerHTML = `
      <div class="empty-setting-box" onclick="openModal('modal-account-info')">
        <div>
          <div class="empty-setting-text">Add Payout Account Info</div>
          <div class="empty-setting-sub">Country, Business type, Phone, Email & TIN</div>
        </div>
        <span class="link-blue" style="font-weight:700;">Add &rsaquo;</span>
      </div>
    `;
  } else {
    accAddBtn.style.display = "none";
    const acc = appState.accountInfo;
    accContainer.innerHTML = `
      <div class="info-field-block">
        <div class="info-field-label">Country</div>
        <div class="info-field-value">${esc(acc.country)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Business type</div>
        <div class="info-field-value">${esc(acc.businessType)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Business phone number</div>
        <div class="info-field-value">${esc(acc.phone)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Email address</div>
        <div class="info-field-value">${esc(acc.email)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Tax identification number (TIN)</div>
        <div class="info-field-value">********${maskNumber(acc.tin)}</div>
      </div>
      <button class="btn-blue-full" onclick="openModal('modal-account-info')">Update</button>
    `;
  }

  // 4. Payout Account Owner
  const ownerContainer = document.getElementById("owner-info-content");
  const ownerAddBtn = document.getElementById("btn-owner-info-add");

  if (!appState.ownerInfo) {
    ownerAddBtn.style.display = "inline-block";
    ownerContainer.innerHTML = `
      <div class="empty-setting-box" onclick="openModal('modal-owner-info')">
        <div>
          <div class="empty-setting-text">Add Payout Account Owner</div>
          <div class="empty-setting-sub">Full name, Owner birthdate & Address</div>
        </div>
        <span class="link-blue" style="font-weight:700;">Add &rsaquo;</span>
      </div>
    `;
  } else {
    ownerAddBtn.style.display = "none";
    const ow = appState.ownerInfo;
    ownerContainer.innerHTML = `
      <div class="info-field-block">
        <div class="info-field-label">Full name</div>
        <div class="info-field-value">${esc(ow.fullName)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Owner birthdate</div>
        <div class="info-field-value">${esc(ow.birthdate)}</div>
      </div>
      <div class="info-field-block">
        <div class="info-field-label">Address</div>
        <div class="info-field-value">
          ${esc(ow.addressLine1)}<br/>
          ${esc(ow.addressLine2)}<br/>
          ${esc(ow.country)}
        </div>
      </div>
      <button class="btn-gray-full" onclick="openModal('modal-owner-info')">Update</button>
    `;
  }

  syncProfileAndAdminUI();
}

// ==================== MODAL HANDLERS ====================
function openModal(modalId) {
  // Pre-fill forms if data already exists
  if (modalId === "modal-payout-method") {
    if (appState.payoutMethod) {
      document.getElementById("pm-holder-name").value = appState.payoutMethod.holderName;
      document.getElementById("pm-account-number").value = appState.payoutMethod.accountNumber;
      const radios = document.querySelectorAll('input[name="pmType"]');
      radios.forEach((r) => (r.checked = r.value === appState.payoutMethod.type));
    } else {
      document.getElementById("pm-holder-name").value = appState.ownerInfo ? appState.ownerInfo.fullName : "";
    }
    toggleMethodFields();
  }

  if (modalId === "modal-tax-info" && appState.taxInfo) {
    document.getElementById("tax-form-type").value = appState.taxInfo.formType;
    document.getElementById("tax-etin-number").value = appState.taxInfo.etin;
  }

  if (modalId === "modal-account-info" && appState.accountInfo) {
    document.getElementById("acc-country").value = appState.accountInfo.country;
    document.getElementById("acc-business-type").value = appState.accountInfo.businessType;
    document.getElementById("acc-phone").value = appState.accountInfo.phone;
    document.getElementById("acc-email").value = appState.accountInfo.email;
    document.getElementById("acc-tin").value = appState.accountInfo.tin;
  }

  if (modalId === "modal-owner-info" && appState.ownerInfo) {
    document.getElementById("owner-fullname").value = appState.ownerInfo.fullName;
    document.getElementById("owner-birthdate").value = appState.ownerInfo.birthdate;
    document.getElementById("owner-address-line1").value = appState.ownerInfo.addressLine1;
    document.getElementById("owner-address-line2").value = appState.ownerInfo.addressLine2;
    document.getElementById("owner-country").value = appState.ownerInfo.country;
  }

  document.getElementById(modalId).classList.add("open");
}

function closeModal(modalId) {
  document.getElementById(modalId).classList.remove("open");
}

function toggleMethodFields() {
  const selected = document.querySelector('input[name="pmType"]:checked').value;
  const numLabel = document.getElementById("pm-number-label");
  const numInput = document.getElementById("pm-account-number");
  const expiryGroup = document.getElementById("pm-expiry-group");

  if (selected === "VISA" || selected === "MasterCard") {
    numLabel.textContent = `${selected} Card Number`;
    numInput.placeholder = "e.g. 4532 •••• •••• 9448";
    expiryGroup.style.display = "block";
  } else {
    numLabel.textContent = `${selected} Account Number`;
    numInput.placeholder = "e.g. 01645092827";
    expiryGroup.style.display = "none";
  }
}

// ==================== SAVE TO SUPABASE ====================
async function saveSetting(column, value) {
  if (!sb) { showToast("লগইন সেশন নেই"); return false; }
  const { error } = await sb.from("payout_settings").upsert(
    { user_id: uid, [column]: value, updated_at: new Date().toISOString() },
    { onConflict: "user_id" }
  );
  if (error) { showToast("সেভ হয়নি: " + error.message); return false; }
  await loadSettings();
  return true;
}

async function savePayoutMethod(e) {
  e.preventDefault();
  const type = document.querySelector('input[name="pmType"]:checked').value;
  const holderName = document.getElementById("pm-holder-name").value.trim();
  let accountNumber = document.getElementById("pm-account-number").value.trim();
  const expiry = document.getElementById("pm-card-expiry").value.trim();
  // কার্ডের পূর্ণ নম্বর রাখা হয় না, শুধু শেষ ৪ ডিজিট
  if (type === "VISA" || type === "MasterCard") accountNumber = accountNumber.replace(/\D/g, "").slice(-4);

  if (await saveSetting("payout_method", { type, holderName, accountNumber, expiry })) {
    closeModal("modal-payout-method");
    showToast(`${type} payout method saved!`);
  }
}

async function saveTaxInfo(e) {
  e.preventDefault();
  const formType = document.getElementById("tax-form-type").value;
  const etin = document.getElementById("tax-etin-number").value.trim();
  if (await saveSetting("tax_info", { formType, etin, updatedDate: fmtDate(new Date()) })) {
    closeModal("modal-tax-info");
    showToast("Tax information saved!");
  }
}

async function saveAccountInfo(e) {
  e.preventDefault();
  const v = {
    country: document.getElementById("acc-country").value.trim(),
    businessType: document.getElementById("acc-business-type").value,
    phone: document.getElementById("acc-phone").value.trim(),
    email: document.getElementById("acc-email").value.trim(),
    tin: document.getElementById("acc-tin").value.trim()
  };
  if (await saveSetting("account_info", v)) {
    closeModal("modal-account-info");
    showToast("Payout account info updated!");
  }
}

async function saveOwnerInfo(e) {
  e.preventDefault();
  const v = {
    fullName: document.getElementById("owner-fullname").value.trim(),
    birthdate: document.getElementById("owner-birthdate").value,
    addressLine1: document.getElementById("owner-address-line1").value.trim(),
    addressLine2: document.getElementById("owner-address-line2").value.trim(),
    country: document.getElementById("owner-country").value.trim()
  };
  if (await saveSetting("owner_info", v)) {
    closeModal("modal-owner-info");
    showToast("Payout account owner saved!");
  }
}

// ==================== PROFILE / ADMIN (মূল আইডি থেকে) ====================
function syncProfileAndAdminUI() {
  const { name, avatar } = appState.loggedInUser;
  const src = avatar || DEFAULT_AVATAR;
  ["header-avatar-1", "header-avatar-2", "settings-admin-avatar"].forEach((id) => {
    const el = document.getElementById(id);
    if (el.getAttribute("src") !== src) {
      el.onerror = () => { el.onerror = null; el.src = DEFAULT_AVATAR; };
      el.src = src;
    }
  });
  document.getElementById("settings-admin-name").textContent = name || "--";
}

// ==================== WITHDRAWAL / PAYOUT REQUEST ====================
function openWithdrawModal() {
  if (!appState.payoutMethod) {
    showToast("আগে Settings-এ Payout Method যোগ করুন");
    setTimeout(() => openSettingsView(), 700);
    return;
  }
  if (appState.actualBalance < MIN_PAYOUT) {
    showToast("Payout নিতে ন্যূনতম $" + MIN_PAYOUT + ".00 লাগবে");
    return;
  }
  document.getElementById("withdraw-avail-label").textContent = "$" + appState.actualBalance.toFixed(2);
  document.getElementById("withdraw-amount").max = appState.actualBalance.toFixed(2);
  document.getElementById("withdraw-amount").value = appState.actualBalance.toFixed(2);
  const pm = appState.payoutMethod;
  document.getElementById("withdraw-method-preview").textContent =
    `${pm.type} • ${pm.holderName} (•••${maskNumber(pm.accountNumber)})`;
  openModal("modal-withdraw");
}

async function submitWithdrawal(e) {
  e.preventDefault();
  const amount = parseFloat(document.getElementById("withdraw-amount").value);
  if (isNaN(amount) || amount < MIN_PAYOUT || amount > appState.actualBalance) {
    showToast("সঠিক পরিমাণ দিন (ন্যূনতম $" + MIN_PAYOUT + ".00)");
    return;
  }
  const pm = appState.payoutMethod;
  const { error } = await sb.from("payout_transactions").insert({
    user_id: uid,
    type: "payout",
    title: `Payout to ${pm.type}`,
    source: `${pm.type} •••${maskNumber(pm.accountNumber)}`,
    amount: amount,
    status: "pending"
  });
  if (error) { showToast("Request ব্যর্থ: " + error.message); return; }
  closeModal("modal-withdraw");
  showToast(`Payout of $${amount.toFixed(2)} requested!`);
  loadTransactions();
}

// ==================== HELPERS ====================
function earningSourceCount() {
  const set = new Set();
  appState.transactions.forEach((t) => { if (t.type === "earning" && t.status === "Completed" && t.source) set.add(t.source); });
  return set.size;
}

function round2(n) { return Math.round(n * 100) / 100; }

function fmtDate(d) {
  return new Date(d).toLocaleDateString("en-US", { month: "short", day: "numeric", year: "numeric" });
}

function esc(v) {
  return String(v == null ? "" : v).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function maskNumber(str) {
  if (!str) return "0000";
  return String(str).replace(/\s+/g, "").slice(-4);
}

function showToast(msg) {
  const toast = document.getElementById("toast");
  toast.textContent = msg;
  toast.classList.add("show");
  setTimeout(() => toast.classList.remove("show"), 2600);
}
