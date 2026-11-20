// FamilyBook Payment bridge — add_payment.html-এর শেষে (</body>-এর আগে, আসল <script> ব্লকের পরে) যুক্ত হবে।
// অ্যাপের ভেতরে (window.FBPayApp আছে) হলে Supabase-এ সেভ হয়; ব্রাউজারে খুললে আগের localStorage-ই চলে।
(function () {
  if (!window.FBPayApp) return;

  var busy = false;

  function esc(s) { return String(s == null ? '' : s).replace(/[<>&"']/g, ''); }

  // ---- কার্ড যাচাই ----
  function luhn(num) {
    var sum = 0, alt = false;
    for (var i = num.length - 1; i >= 0; i--) {
      var n = parseInt(num.charAt(i), 10);
      if (alt) { n *= 2; if (n > 9) n -= 9; }
      sum += n; alt = !alt;
    }
    return sum % 10 === 0;
  }
  function detectBrand(d) {
    if (/^4\d{12}(\d{3})?(\d{3})?$/.test(d)) return 'Visa';
    if (/^(5[1-5]\d{14}|2(2[2-9]\d|[3-6]\d\d|7[01]\d|720)\d{12})$/.test(d)) return 'Mastercard';
    return '';
  }

  // ---- Screen 1-এর ব্যাক বাটন = পেজ বন্ধ ----
  var backBtn = document.querySelector('#screen-1 header button');
  if (backBtn) backBtn.onclick = function () { FBPayApp.close(); };

  // ---- অ্যান্ড্রয়েড ব্যাক বাটনের জন্য ----
  window.fbPayBack = function () {
    var active = document.querySelector('.screen.active');
    if (!active) return false;
    var map = { 'screen-3': 'screen-2', 'screen-2': 'screen-1', 'screen-4': 'screen-1', 'screen-5': 'screen-1' };
    if (map[active.id]) { openScreen(map[active.id]); return true; }
    return false;
  };

  // ---- লোড ----
  window.onload = function () { FBPayApp.load(); };

  window.fbPayLoaded = function (d) {
    savedCard = d.card || null;
    savedTax = (d.tax && typeof d.tax === 'object') ? d.tax : null;
    activities = (d.activities || []).map(function (a) {
      var dt = a.created_at ? new Date(a.created_at) : new Date();
      return {
        title: esc(a.title),
        amount: Number(a.amount) || 0,
        status: esc(a.status),
        card: esc(a.card_label),
        date: dt.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
      };
    });
    renderCardPreview();
    renderBusinessInfo();
    renderActivities();
  };

  // ---- নেটিভ রেজাল্ট ----
  window.fbPayResult = function (op, ok, msg) {
    busy = false;
    if (op === 'saveCard') {
      if (!ok) {
        var err = document.getElementById('card-error-text');
        err.textContent = msg; err.classList.remove('hidden');
        return;
      }
      ['input-card-name', 'input-card-number', 'input-card-expiry', 'input-card-cvv'].forEach(function (id) {
        document.getElementById(id).value = '';
      });
      document.getElementById('detected-brand-pill').classList.add('hidden');
      openScreen('screen-1');
    } else if (op === 'saveTax' && ok) {
      openScreen('screen-1');
    }
    showToast(msg);
  };

  // ---- কার্ড সেভ: পুরো নম্বর/CVV অ্যাপে যায় না, শুধু last4 ----
  window.saveCardDetails = function () {
    if (busy) return;
    var nameEl = document.getElementById('input-card-name');
    var numEl = document.getElementById('input-card-number');
    var expEl = document.getElementById('input-card-expiry');
    var cvvEl = document.getElementById('input-card-cvv');
    var errEl = document.getElementById('card-error-text');

    var holder = nameEl.value.trim();
    var digits = numEl.value.replace(/\D/g, '');
    var expiry = expEl.value.trim();
    var cvv = cvvEl.value.trim();
    var brand = detectBrand(digits);

    function fail(m, el) { errEl.textContent = m; errEl.classList.remove('hidden'); if (el) el.focus(); }
    errEl.classList.add('hidden');

    if (!holder) return fail('Please enter the name on your card.', nameEl);
    if (!brand) return fail('Only Visa or Mastercard is supported.', numEl);
    if (!luhn(digits)) return fail('Please enter a valid card number.', numEl);
    if (!/^(0[1-9]|1[0-2])\/\d{2}$/.test(expiry)) return fail('Please enter expiry date in MM/YY format.', expEl);
    if (!/^\d{3,4}$/.test(cvv)) return fail('Please enter a valid 3 or 4 digit CVV.', cvvEl);

    busy = true;
    FBPayApp.saveCard(JSON.stringify({
      brand: brand, holder: holder, last4: digits.slice(-4), expiry: expiry
    }));
    digits = null; cvv = null; cvvEl.value = '';   // CVV সাথে সাথে মুছে ফেলা
  };

  window.removeSavedCard = function () { FBPayApp.removeCard(); };

  window.saveBusinessInfo = function () {
    var g = function (id) { return document.getElementById(id).value.trim(); };
    FBPayApp.saveTax(JSON.stringify({
      country: g('tax-country'), currency: g('tax-currency'), timezone: g('tax-timezone'),
      businessName: g('tax-business-name'), tinAddress: g('tax-tin-address'),
      city: g('tax-city'), state: g('tax-state'), zip: g('tax-zip')
    }));
  };

  window.addTestActivity = function (title, amount, status) {
    if (!savedCard) { showToast('Please add a Visa/Mastercard first'); return; }
    document.getElementById('purchase-simulator-box').classList.add('hidden');
    FBPayApp.addActivity(JSON.stringify({ title: title, amount: amount, status: status }));
  };

  window.clearActivities = function () { FBPayApp.clearActivities(); };
})();
