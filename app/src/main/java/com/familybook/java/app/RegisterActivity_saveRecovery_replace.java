// RegisterActivity.java-তে পুরনো saveRecovery(...) মেথডটি মুছে এই দুটি মেথড বসান (একই ক্লাসের ভেতরে)।
// এতে রেজিস্ট্রেশনে NID/BRC ভেরিফাই হলে Settings > Accounts & Privacy পেজে সেটা "Verified" দেখাবে
// এবং ভেরিফাইয়ের নাম, নম্বর, জন্মদিন, জাতীয়তা, জেন্ডার সেখানে বসে থাকবে।

    /** ইমেইল কোড ঠিক হওয়ার পর (লগইন অবস্থায়) চলে। NID নম্বর সার্ভারে সিক্রেট পেপার দিয়ে হ্যাশ হয়; ব্যর্থ হলেও সাইন-আপ আটকায় না */
    private void saveRecovery(final Runnable done) {
        if (!nidVerified || nidNumber == null) { done.run(); return; }
        try {
            JSONObject b = new JSONObject()
                    .put("p_nid", nidNumber)
                    .put("p_nid_name", s(etNidName))
                    .put("p_birth", dobValue())
                    .put("p_email", s(etEmail).toLowerCase())
                    .put("p_agu", s(etAgu))
                    .put("p_name", s(etName));
            SupabaseRest.call("POST", "/rest/v1/rpc/save_nid_recovery", b.toString(), null, true,
                    r -> markNidVerifiedInProfile(done));
        } catch (Exception e) {
            done.run();
        }
    }

    /** Settings পেজ যেন জানে: রেজিস্ট্রেশনেই NID/BRC ভেরিফাই হয়েছে (profiles.info-তে লেখা হয়) */
    private void markNidVerifiedInProfile(final Runnable done) {
        Db.getMine("profiles", (row, err) -> {
            if (err != null) { done.run(); return; }
            try {
                JSONObject info = row != null && row.optJSONObject("info") != null
                        ? row.optJSONObject("info") : new JSONObject();
                String phone = phoneValue();
                info.put("nidVerified", true);
                info.put("nidName", s(etNidName));
                info.put("nidNo", nidNumber);
                info.put("dob", dobValue() == null ? "" : dobValue());
                info.put("nationality", s(etNation));
                info.put("gender", gender == null ? "" : gender);
                info.put("genderVerified", verGender != null && verGender.equals(gender));
                info.put("blood", blood);
                info.put("phone", phone == null ? "" : phone);
                Db.patchMyProfile(new JSONObject().put("info", info), e2 -> done.run());
            } catch (Exception ex) {
                done.run();
            }
        });
    }
