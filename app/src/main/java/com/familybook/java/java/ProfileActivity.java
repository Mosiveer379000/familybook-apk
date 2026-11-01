package com.familybook.app;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public class ProfileActivity extends BaseActivity implements PostOps.Refreshable {

    /** পোস্ট বদলালে (পিন/প্রাইভেসি/ডিলিট...) প্রোফাইল নতুন করে লোড */
    @Override
    public void refreshPosts() {
        if (!isFinishing() && this.uid != null) loadData();
    }

    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"};
    private PostAdapter adapter;
    private static final boolean SHOW_USERNAME_ON_PROFILE = false;
    private boolean postsEmpty = false;
    private List<Post> allPosts = new ArrayList<>();   // পিন-ক্রমে সব পোস্ট; ট্যাব অনুযায়ী ছেঁকে দেখানো হয়
    private int curTab = ProfileMedia.TAB_SHORTS;   // সব জায়গা থেকে প্রোফাইল খুললে ডিফল্টে Shorts ট্যাব
    private boolean hasAvatarPhoto = false;
    private ImageView imgVerified;
    private boolean pVerifiedBadge;
    private LinearLayout bioBox;
    private ImageView btnEditUser;
    private TextView btnFollow;
    private LinearLayout layoutVisitorActions;
    private ImageView btnFollowImg, btnMessage, imgFollowerState;
    private boolean followsMe;
    private String fallbackName;
    private boolean following;
    private ImageView imgAvatar;
    private ImageView imgCover;
    private boolean isMe;
    private LinearLayout layoutDashboardSettings;
    private View moreBox;
    private String pBio;
    private String pBioPrivacy;
    private String pBirthday;
    private String pCity;
    private String pEdu;
    private String pHome;
    private boolean pHomeLocked;
    private LinearLayout homeCard;
    private LinearLayout detailsBox;   // Personal details + Pin details + Followers
    private TextView homeCardLabel, homeCardAddr, homeCardHint;
    private ImageView homePrivacyIcon;
    private LinearLayout homeInner;
    private Snap pHomeSnap;
    private Snap pProfSnap;
    private boolean pHomeFilled;
    private Snap pInfo;
    private long pJoined;
    private String pName;
    private String pUsername;
    private String pWork;
    private ActivityResultLauncher<String> picker;
    private ActivityResultLauncher<Intent> cropLauncher;
    private boolean profileLoaded;
    private boolean profileMissing;
    private TextView tabAll;
    private TextView tabMore;
    private TextView tabPhotos;
    private TextView tabPosts;
    private TextView tabShorts;
    private ProfileShorts shorts;
    private LinearLayout topBox;
    private TextView tvBio;
    private TextView tvBirthday;
    private TextView tvCity;
    private TextView tvEdu;
    private TextView tvEmail;
    private TextView tvFollowers;
    private TextView tvFollowing;
    private TextView tvHome;
    private TextView tvJoined;
    private TextView tvName;
    private TextView tvNoPosts;
    private TextView tvPhone;
    private TextView tvPosts;
    private TextView tvSeeMore;
    private TextView tvUser;
    private TextView tvWork;
    private String uDob;
    private String uHome;
    private boolean uHomePrivate;
    private String uid;
    private boolean usersLoaded;
    private String myName = "Family Member";
    private String pickTarget = "avatar";

    public static void open(Context c, String uid, String name) {
        if (uid != null) {
            c.startActivity(new Intent(c, ProfileActivity.class).putExtra("uid", uid).putExtra("name", name));
        }
    }

    public static String birthdayFromDob(String dob) {
        try {
            String[] p = dob.trim().split("/");
            return MONTHS[Integer.parseInt(p[1]) - 1] + " " + Integer.parseInt(p[0]);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Me.isLoggedIn() || Me.uid() == null) {
            finish();
            return;
        }
        String extraUid = getIntent().getStringExtra("uid");
        this.uid = extraUid;
        if (extraUid == null) {
            this.uid = Me.uid();
        }
        this.fallbackName = getIntent().getStringExtra("name");
        this.isMe = this.uid.equals(Me.uid());
        setContentView(R.layout.activity_profile);
        bindBell(findViewById(R.id.bellContainer), findViewById(R.id.tvBadge));
        Sfx.init(this);
        this.picker = registerForActivityResult(new ActivityResultContracts.GetContent(), new ActivityResultCallback<Uri>() {
            @Override
            public void onActivityResult(Uri uri) {
                if (uri != null) {
                    cropLauncher.launch(CropActivity.intent(ProfileActivity.this, uri, pickTarget));
                }
            }
        });
        this.cropLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<androidx.activity.result.ActivityResult>() {
            @Override
            public void onActivityResult(androidx.activity.result.ActivityResult r) {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    String path = r.getData().getStringExtra("path");
                    if (path != null) {
                        saveCroppedImage(path);
                    }
                }
            }
        });
        bindViews();
        setupNav();
        applyTopBars();
        setupStickyTabs();
        setupAdapter();
        setupActions();
        applyFeedVisibility();   // শুরুতেই Shorts ট্যাব দেখাবে (পোস্টের লিস্ট লুকানো থাকবে)
        PostOps.loadMyName(Me.uid(), new PostOps.NameCb() {
            @Override
            public void on(String n) {
                ProfileActivity.this.myName = n;
            }
        });
        loadData();
    }

    private void bindViews() {
        this.imgCover = (ImageView) findViewById(R.id.imgCover);
        this.imgAvatar = (ImageView) findViewById(R.id.imgAvatar);
        this.tvName = (TextView) findViewById(R.id.tvName);
        this.tvFollowers = (TextView) findViewById(R.id.tvFollowers);
        this.tvFollowing = (TextView) findViewById(R.id.tvFollowing);
        this.tvPosts = (TextView) findViewById(R.id.tvPosts);
        this.tvBio = (TextView) findViewById(R.id.tvBio);
        this.tvCity = (TextView) findViewById(R.id.tvCity);
        this.tvHome = (TextView) findViewById(R.id.tvHome);
        this.tvBirthday = (TextView) findViewById(R.id.tvBirthday);
        this.tvWork = (TextView) findViewById(R.id.tvWork);
        this.tvEdu = (TextView) findViewById(R.id.tvEdu);
        this.tvJoined = (TextView) findViewById(R.id.tvJoined);
        this.tvEmail = (TextView) findViewById(R.id.tvEmail);
        this.tvPhone = (TextView) findViewById(R.id.tvPhone);
        this.tvSeeMore = (TextView) findViewById(R.id.tvSeeMore);
        this.btnFollow = (TextView) findViewById(R.id.btnFollow);
        this.layoutVisitorActions = (LinearLayout) findViewById(R.id.layoutVisitorActions);
        this.btnFollowImg = (ImageView) findViewById(R.id.btnFollowImg);
        this.btnMessage = (ImageView) findViewById(R.id.btnMessage);
        this.imgFollowerState = (ImageView) findViewById(R.id.imgFollowerState);
        this.layoutDashboardSettings = (LinearLayout) findViewById(R.id.layoutDashboardSettings);
        this.tvNoPosts = (TextView) findViewById(R.id.tvNoPosts);
        this.moreBox = findViewById(R.id.moreBox);
        this.tabAll = (TextView) findViewById(R.id.tabAll);
        this.tabPhotos = (TextView) findViewById(R.id.tabPhotos);
        this.tabPosts = (TextView) findViewById(R.id.tabPosts);
        this.tabShorts = (TextView) findViewById(R.id.tabShorts);
        this.tabMore = (TextView) findViewById(R.id.tabMore);
        ((View) this.imgCover.getParent()).getLayoutParams().height = (getResources().getDisplayMetrics().widthPixels * 319) / 840;
        LinearLayout userRow = new LinearLayout(this);
        userRow.setOrientation(0);
        userRow.setGravity(Gravity.CENTER);
        TextView textView = new TextView(this);
        this.tvUser = textView;
        textView.setTextSize(15.0f);
        this.tvUser.setTextColor(Color.parseColor("#65676B"));
        ImageView editUserIcon = new ImageView(this);
        this.btnEditUser = editUserIcon;
        editUserIcon.setImageResource(R.drawable.ic_edit);
        editUserIcon.setColorFilter(Color.parseColor("#1C1E21"), android.graphics.PorterDuff.Mode.SRC_IN);
        editUserIcon.setPadding(dp(4), dp(4), dp(4), dp(4));
        editUserIcon.setVisibility(8);
        userRow.addView(this.tvUser);
        LinearLayout.LayoutParams euLp = new LinearLayout.LayoutParams(dp(26), dp(26));
        euLp.setMarginStart(dp(6));
        userRow.addView(this.btnEditUser, euLp);
        View statsRowView = findViewById(R.id.statsRow);
        ViewGroup nameCol = (ViewGroup) findViewById(R.id.profileCol);
        nameCol.addView(userRow, nameCol.indexOfChild(statsRowView) + 1);
        userRow.setVisibility(SHOW_USERNAME_ON_PROFILE ? View.VISIBLE : View.GONE);
        this.tvName.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(16) - dp(64) - dp(30));
        this.tvName.setTextColor(Color.parseColor("#1C1E21"));
        this.imgVerified = (ImageView) findViewById(R.id.imgVerified);
        applyVerifiedBadge();
        findViewById(R.id.btnCoverSearch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(ProfileActivity.this, SearchActivity.class)); }
        });
        findViewById(R.id.btnCoverMore).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { soon("Menu"); }
        });
        findViewById(R.id.btnNameMenu).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { soon("Menu"); }
        });
        if (this.isMe) {
            View.OnClickListener editUser = new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editUsername();
                }
            };
            this.tvUser.setOnClickListener(editUser);
            this.btnEditUser.setOnClickListener(editUser);
        }
        this.bioBox = new LinearLayout(this);
        this.topBox = new LinearLayout(this);
        View bioRow = (View) this.tvBio.getParent();
        ViewGroup col = (ViewGroup) bioRow.getParent();
        col.addView(this.bioBox, col.indexOfChild(bioRow) + 1);
        ViewGroup cityParent = (ViewGroup) this.tvCity.getParent();
        cityParent.addView(this.topBox, cityParent.indexOfChild(this.tvCity));
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(1);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(4), Color.parseColor("#0866FF"));
        this.imgAvatar.setBackground(ring);
        raiseAvatarAboveCover();
        updateTabSelection(this.tabShorts);
        styleVisitorTabs();
        stat(this.tvFollowers, 0, "Follower");
        stat(this.tvFollowing, 0, "Following");
        stat(this.tvPosts, 0, "Post");
        TextView textView3 = this.tvName;
        String str = this.fallbackName;
        if (str == null) {
            str = "Member";
        }
        textView3.setText(str);
        this.imgAvatar.setImageResource(R.drawable.ic_profile);
    }

    private void updateTabSelection(TextView selectedTab) {
        TextView[] tabs = {this.tabAll, this.tabPhotos, this.tabShorts, this.tabPosts, this.tabMore};
        for (TextView t : tabs) {
            t.setBackground(null);
            t.setTextColor(Color.parseColor("#65676B"));
        }
        GradientDrawable selectedPill = new GradientDrawable();
        selectedPill.setCornerRadius((float) dp(30));
        selectedPill.setColor(Color.parseColor("#E4E6EB"));
        selectedTab.setBackground(selectedPill);
        selectedTab.setTextColor(Color.parseColor("#0866FF"));
    }

    /** অন্যের প্রোফাইলে ট্যাবগুলো ছোট ও কমপ্যাক্ট (নিজের প্রোফাইল আগের মতই) */
    private void styleVisitorTabs() {
        if (this.isMe) return;
        TextView[] tabs = {this.tabAll, this.tabPhotos, this.tabShorts, this.tabPosts, this.tabMore};
        for (TextView t : tabs) {
            t.setTextSize(14f);
            t.setPadding(dp(16), dp(7), dp(16), dp(7));
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) t.getLayoutParams();
            lp.setMarginEnd(dp(t == this.tabMore ? 16 : 6));
            t.setLayoutParams(lp);
        }
    }

    private void raiseAvatarAboveCover() {
        java.util.HashSet<View> coverChain = new java.util.HashSet<>();
        View v = this.imgCover;
        while (v != null) {
            coverChain.add(v);
            if (!(v.getParent() instanceof View)) {
                break;
            }
            v = (View) v.getParent();
        }
        View avatarBranch = this.imgAvatar;
        while ((avatarBranch.getParent() instanceof View) && !coverChain.contains((View) avatarBranch.getParent())) {
            avatarBranch = (View) avatarBranch.getParent();
        }
        if (!(avatarBranch.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup common = (ViewGroup) avatarBranch.getParent();
        View coverBranch = this.imgCover;
        while (coverBranch.getParent() != common && (coverBranch.getParent() instanceof View)) {
            coverBranch = (View) coverBranch.getParent();
        }
        android.view.ViewParent pp = this.imgAvatar.getParent();
        while (pp instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) pp;
            g.setClipChildren(false);
            g.setClipToPadding(false);
            if (g == common) {
                break;
            }
            pp = g.getParent();
        }
        coverBranch.setElevation(0.0f);
        avatarBranch.setElevation((float) dp(8));
        this.imgAvatar.setElevation((float) dp(8));
    }

    private void applyDefaultAvatar() {
        if (this.hasAvatarPhoto) return;
        String g = this.pProfSnap == null ? null
                : this.pProfSnap.child("info").child("gender").getValue(String.class);
        DefaultAvatar.remember(this.uid, g);
        DefaultAvatar.apply(this.imgAvatar, g);
    }

    private void applyVerifiedBadge() {
        if (this.imgVerified == null) return;
        if (this.pVerifiedBadge) {
            this.imgVerified.clearColorFilter();
            this.imgVerified.setAlpha(1f);
        } else {
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setSaturation(0f);
            android.graphics.ColorMatrix dim = new android.graphics.ColorMatrix();
            dim.setScale(0.45f, 0.45f, 0.45f, 1f);
            cm.postConcat(dim);
            this.imgVerified.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            this.imgVerified.setAlpha(0.85f);
        }
    }

    private int dp(int v) {
        return (int) ((((float) v) * getResources().getDisplayMetrics().density) + 0.5f);
    }

    private void setupNav() {
        // নিচের নেভিগেশন বার (Home · Gallery · Shop · Dashboard · Profile) — সব ট্যাবেই থাকে
        BottomNav.attach(this);

        findViewById(R.id.navHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        
        findViewById(R.id.navMembers).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(ProfileActivity.this, MembersActivity.class));
            }
        });

        findViewById(R.id.navGallery).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(ProfileActivity.this, MyPostsActivity.class));
            }
        });
        findViewById(R.id.navProfile).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!isMe) {
                    open(ProfileActivity.this, Me.uid(), myName);
                }
            }
        });
        findViewById(R.id.navMenu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                soon("Menu");
            }
        });
        findViewById(R.id.navLogout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmLogout();
            }
        });
    }

    private void setupAdapter() {
        RecyclerView rv = (RecyclerView) findViewById(R.id.rvProfilePosts);
        rv.setLayoutManager(new LinearLayoutManager(this));
        PostAdapter postAdapter = new PostAdapter(new PostAdapter.Actions() {
            @Override
            public void onLike(Post p) {
                ProfileActivity.this.toggleLike(p);
            }

            @Override
            public void onHide(Post p) {
            }

            @Override
            public void onMenu(Post p, View anchor) {
                ProfileActivity profileActivity = ProfileActivity.this;
                PostOps.showMenu(profileActivity, Me.uid(), p, anchor, false);
            }

            @Override
            public void onComments(Post p) {
                ProfileActivity.this.startActivity(new Intent(ProfileActivity.this, CommentsActivity.class).putExtra("postId", p.id));
            }
        }, false, true);   // হোম পেজের পোস্টকার্ডের (item_post_home) মতো
        this.adapter = postAdapter;
        rv.setAdapter(postAdapter);
        ViewGroup rvParent = (ViewGroup) rv.getParent();
        this.shorts = new ProfileShorts(this, rvParent, rvParent.indexOfChild(rv));
    }

    private void setupActions() {
        this.tvSeeMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int i = 0;
                boolean open = moreBox.getVisibility() == 0;
                View v = moreBox;
                if (open) {
                    i = 8;
                }
                v.setVisibility(i);
                tvSeeMore.setText(open ? "See more details" : "See less");
            }
        });
        this.tabAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateTabSelection(tabAll);
                showTab(ProfileMedia.TAB_ALL);
            }
        });
        this.tabPhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Photos এখন আলাদা পুরো পেজ; ট্যাবের সিলেকশন আগেরটাই থাকে
                startActivity(new Intent(ProfileActivity.this, ProfilePhotosActivity.class)
                        .putExtra("uid", uid)
                        .putExtra("isMe", isMe));
            }
        });
        this.tabShorts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateTabSelection(tabShorts);
                showTab(ProfileMedia.TAB_SHORTS);
            }
        });
        this.tabPosts.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateTabSelection(tabPosts);
                showTab(ProfileMedia.TAB_POSTS);
            }
        });
        
        this.tabMore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateTabSelection(tabMore);
                Intent intent = new Intent(ProfileActivity.this, PostBinActivity.class);
                intent.putExtra("mode", PostBinActivity.MODE_ARCHIVE);
                startActivity(intent);
            }
        });

        findViewById(R.id.btnDashboard).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                soon("Dashboard");
            }
        });
        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                soon("Settings");
            }
        });
        this.tvFollowers.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPeople("Followers", "followers");
            }
        });
        this.tvFollowing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPeople("Following", "following");
            }
        });
        if (this.isMe) {
            View ec = findViewById(R.id.btnEditCover);
            View ea = findViewById(R.id.btnEditAvatar);
            View en = findViewById(R.id.btnEditName);
            View eb = findViewById(R.id.btnEditBio);
            View ed = findViewById(R.id.btnEditDetails);
            ec.setVisibility(0);
            ea.setVisibility(0);
            en.setVisibility(8);
            eb.setVisibility(0);
            ed.setVisibility(0);
            ec.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pick("cover");
                }
            });
            ea.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pick("avatar");
                }
            });
            this.imgAvatar.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    pick("avatar");
                }
            });
            en.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editSingle("Edit name", pName, 60, "name");
                }
            });
            eb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openBioEditor();
                }
            });
            ed.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editDetails();
                }
            });
            this.tvCity.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editDetails();
                }
            });
            this.tvHome.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    editDetails();
                }
            });
            TextView textView = this.btnFollow;
            if (textView != null) {
                textView.setVisibility(8);
            }
            LinearLayout linearLayout = this.layoutDashboardSettings;
            if (linearLayout != null) {
                linearLayout.setVisibility(0);
                return;
            }
            return;
        }
        this.btnFollow.setVisibility(8);
        this.layoutVisitorActions.setVisibility(0);
        this.btnFollowImg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (following) toast("Long press the Following button for options");
                else toggleFollow();
            }
        });
        this.btnFollowImg.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (!following) return false;
                showFollowMenu();
                return true;
            }
        });
        this.btnMessage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openFamilyChatWith();
            }
        });
        LinearLayout linearLayout2 = this.layoutDashboardSettings;
        if (linearLayout2 != null) {
            linearLayout2.setVisibility(8);
        }
        styleFollow();
    }

    private boolean resumedOnce = false;

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce && this.uid != null && this.imgAvatar != null) {
            loadData();
        }
        resumedOnce = true;
        final RecyclerView prv = (RecyclerView) findViewById(R.id.rvProfilePosts);
        if (adapter != null && prv != null) prv.postDelayed(adapter::autoplayNow, 400);   // ভিউয়ার থেকে ফিরে অটো-প্লে
    }

    @Override
    protected void onPause() {
        super.onPause();
        ShortPlayerView.stopAll();
    }

    @Override
    protected void onDestroy() {
        if (shorts != null) shorts.setVisible(false);   // আপলোড-লিসেনার ছেড়ে দেয়, Activity লিক হয় না
        super.onDestroy();
    }

    private static final String[][] PROFILE_ALIAS = {{"pin_card", "pinCard"}, {"nid_verified", "nidVerified"}};
    private static final String[][] HOME_ALIAS = {{"home_privacy", "homePrivacy"}, {"post_office", "postOffice"}, {"post_code", "postCode"}};
    private static final String[][] CITY_ALIAS = {{"city_privacy", "cityPrivacy"}, {"post_office", "postOffice"}, {"post_code", "postCode"}};
    private static final String[][] BIRTH_ALIAS = {{"birth_date", "birthDate"}, {"full_name", "fullName"}, {"show_year", "showYear"}, {"id_submitted", "idSubmitted"}};

    private static JSONObject aliased(JSONObject src, String[][] pairs) {
        try {
            JSONObject out = new JSONObject(src.toString());
            for (String[] p : pairs) {
                if (src.has(p[0]) && !src.isNull(p[0])) out.put(p[1], src.opt(p[0]));
            }
            return out;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject profileSnapJson(JSONObject row) {
        JSONObject out = aliased(row, PROFILE_ALIAS);
        try {
            out.put("joinedAt", isoToMs(row.optString("joined_at", null)));
        } catch (Exception ignored) { }
        return out;
    }

    private static long isoToMs(String iso) {
        if (iso == null || iso.length() < 19) return 0L;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(iso.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static JSONArray arrOf(String body) {
        try {
            return new JSONArray(body);
        } catch (Exception e) {
            return null;
        }
    }

    private void loadData() {
        loadPinned();
        // বায়ো + প্রাইভেসি আলাদা টেবিল profile_bios থেকে আসে (RLS: public / friends=ফলোয়ার / locked=শুধু নিজে)
        SupabaseRest.call("GET",
                "/rest/v1/profile_bios?user_id=eq." + this.uid + "&select=bio,bio_privacy&limit=1",
                null, null, true, br -> {
            if (isFinishing() || !br.ok()) return;
            JSONArray ba = arrOf(br.body);
            JSONObject bo = (ba == null || ba.length() == 0) ? null : ba.optJSONObject(0);
            this.pBio = (bo == null || bo.isNull("bio")) ? null : bo.optString("bio", null);
            this.pBioPrivacy = (bo == null || bo.isNull("bio_privacy")) ? null : bo.optString("bio_privacy", null);
            if (this.profileLoaded) render();
        });

        Db.get("profiles", "id", this.uid, (row, err) -> {
            if (isFinishing()) return;
            this.profileMissing = row == null && err == null;
            Snap s = row == null ? Snap.NONE : Snap.of(profileSnapJson(row));
            this.pName = s.child("name").getValue(String.class);
            this.pUsername = s.child("username").getValue(String.class);
            this.pCity = s.child("city").getValue(String.class);
            this.pProfSnap = s;
            if (this.isMe && row != null && (!nz(this.pName) || "Family Member".equals(this.pName))) healProfile(false, null);
            String pk = s.child("pinCard").getValue(String.class);
            if (pk == null && Boolean.TRUE.equals(s.child("pins").child("hometown").getValue(Boolean.class))) pk = "hometown";
            this.pPinKey = pk;
            this.pNidVerified = Boolean.TRUE.equals(s.child("nidVerified").getValue(Boolean.class));
            this.pBirthday = s.child("birthday").getValue(String.class);
            this.pInfo = s.child("info");
            this.bUpdated = nz(this.pInfo.child("birthdayUpdated").getValue(String.class));
            this.pWork = s.child("work").getValue(String.class);
            this.pEdu = s.child("education").getValue(String.class);
            Long j = s.child("joinedAt").getValue(Long.class);
            this.pJoined = j == null ? 0 : j.longValue();
            String ak = s.child("avatar_key").getValue(String.class);
            if (ak != null) loadPhoto(ak, false);
            else { this.hasAvatarPhoto = false; applyDefaultAvatar(); }
            String ck = s.child("cover_key").getValue(String.class);
            if (ck != null) loadPhoto(ck, true);
            else if (this.imgCover != null) this.imgCover.setImageDrawable(null);
            this.profileLoaded = true;
            render();
            renderInfo();
            maybeCreateProfile();
        });

        Db.get("home_info", "user_id", this.uid, (row, err) -> {
            if (isFinishing()) return;
            Snap s = row == null ? Snap.NONE : Snap.of(aliased(row, HOME_ALIAS));
            this.pHome = s.child("hometown").getValue(String.class);
            this.pHomeSnap = s;
            this.pHomeLocked = "locked".equals(s.child("homePrivacy").getValue(String.class));
            this.pHomeFilled = nz(this.pHome);
            this.uHome = this.pHome;
            this.uHomePrivate = this.pHomeLocked;
            render();
        });

        Db.get("city_info", "user_id", this.uid, (row, err) -> {
            if (isFinishing()) return;
            Snap s = row == null ? Snap.NONE : Snap.of(aliased(row, CITY_ALIAS));
            this.pCitySummary = s.child("city").getValue(String.class);
            this.pCitySnap = s;
            this.pCityLocked = "locked".equals(s.child("cityPrivacy").getValue(String.class));
            this.cFilled = nz(this.pCitySummary);
            render();
        });

        Db.get("birth_info", "user_id", this.uid, (row, err) -> {
            if (isFinishing()) return;
            Snap s = row == null ? Snap.NONE : Snap.of(aliased(row, BIRTH_ALIAS));
            this.bDate = s.child("birthDate").getValue(String.class);
            this.bFullName = s.child("fullName").getValue(String.class);
            this.bShowYear = Boolean.TRUE.equals(s.child("showYear").getValue(Boolean.class));
            this.bLocked = "locked".equals(s.child("privacy").getValue(String.class));
            this.bIdSubmitted = Boolean.TRUE.equals(s.child("idSubmitted").getValue(Boolean.class));
            this.uDob = this.bDate;
            this.usersLoaded = true;
            render();
            maybeCreateProfile();
        });

        Db.get("nid_info", "user_id", this.uid, (row, err) -> {
            if (isFinishing()) return;
            Snap s = row == null ? Snap.NONE : Snap.of(row);
            this.nNumber = s.child("number").getValue(String.class);
            this.nStatus = s.child("status").getValue(String.class);
            this.nLocked = !"public".equals(s.child("privacy").getValue(String.class));
            this.nFilled = nz(this.nNumber);
            boolean ver = this.pNidVerified || "verified".equals(this.nStatus);
            if (this.isMe && ver && !this.nidCleaned) {
                this.nidCleaned = true;
                SupabaseRest.call("DELETE", "/rest/v1/nid_requests?user_id=eq." + this.uid,
                        null, "return=minimal", true, r -> { });
            }
            render();
        });

        SupabaseRest.call("GET", "/rest/v1/follows?followee_id=eq." + this.uid + "&select=follower_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            JSONArray a = arrOf(r.body);
            int n = a == null ? 0 : a.length();
            stat(this.tvFollowers, (long) n, "Follower");
            loadFollowerStrip(a);
            boolean f = false;
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && Me.uid() != null && Me.uid().equals(o.optString("follower_id"))) f = true;
                }
            }
            this.following = f;
            styleFollow();
        });

        SupabaseRest.call("GET", "/rest/v1/follows?follower_id=eq." + this.uid + "&select=followee_id&limit=5000",
                null, null, true, r -> {
            if (isFinishing() || !r.ok()) return;
            JSONArray a = arrOf(r.body);
            stat(this.tvFollowing, (long) (a == null ? 0 : a.length()), "Following");
            boolean fm = false;
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null && Me.uid() != null && Me.uid().equals(o.optString("followee_id"))) fm = true;
                }
            }
            this.followsMe = fm;
            styleFollow();
        });

        Db.list("profile_posts?profile_user_id=eq." + this.uid + "&order=created_at.desc&limit=500", (rows, err) -> {
            if (isFinishing() || rows == null) return;
            List<Post> list = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.optJSONObject(i);
                if (o == null) continue;
                Post p = Post.from(o, Me.uid());
                if ("active".equals(p.status) && (this.isMe || "public".equals(p.privacy))) {
                    list.add(p);
                }
            }
            // পিন করা পোস্ট একদম শুরুতে
            Db.get("profiles", "id", this.uid, (prow, perr) -> {
                if (isFinishing()) return;
                String pin = "";
                JSONObject pinfo = prow == null ? null : prow.optJSONObject("info");
                if (pinfo != null) pin = pinfo.optString("pinnedPost", "");
                if (this.isMe) PostOps.myPinnedId = pin;
                String shownPin = "";
                if (!pin.isEmpty()) {
                    for (int i = 0; i < list.size(); i++) {
                        if (pin.equals(list.get(i).id)) {
                            list.add(0, list.remove(i));
                            shownPin = pin;
                            break;
                        }
                    }
                }
                this.adapter.pinnedPostId = shownPin;
                this.allPosts = list;
                stat(this.tvPosts, (long) list.size(), "Post");
                refreshStatusList();
                PostImage.prefetch(list, () -> refreshStatusList());
                // "X updated his profile picture" / feeling / with / at লাইন প্রোফাইল টাইমলাইনেও দেখাতে
                PostExtras.load(() -> { if (!isFinishing()) refreshStatusList(); });
            });
        });
    }


    /**
     * All / Photos / Shorts / Status / More ট্যাব বার: নিচে স্ক্রল করলে উপরে গিয়ে স্থির থাকে (Facebook-এর মত)।
     * আলাদা কপি নয় — আসল ট্যাব বারটাকেই স্ক্রলের সাথে translationY দিয়ে উপরে ধরে রাখা হয়।
     */
    private void setupStickyTabs() {
        final android.widget.ScrollView sv = (android.widget.ScrollView) findViewById(R.id.profileScroll);
        if (sv == null || tabAll == null) return;
        View row = (View) tabAll.getParent();
        final View bar = (row.getParent() instanceof android.widget.HorizontalScrollView)
                ? (View) row.getParent() : row;
        if (!(bar.getParent() instanceof ViewGroup)) return;
        final ViewGroup content = (ViewGroup) bar.getParent();
        int idx = content.indexOfChild(bar);
        final View divider = (idx + 1 < content.getChildCount()) ? content.getChildAt(idx + 1) : null;

        // স্থির অবস্থায় নিচের কনটেন্ট যেন ট্যাবের ফাঁকে দেখা না যায়: margin এর বদলে padding + সাদা ব্যাকগ্রাউন্ড
        ViewGroup.LayoutParams raw = bar.getLayoutParams();
        if (raw instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) raw;
            int top = mlp.topMargin;
            int bottom = mlp.bottomMargin;
            mlp.topMargin = 0;
            mlp.bottomMargin = 0;
            bar.setLayoutParams(mlp);
            bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop() + top,
                    bar.getPaddingRight(), bar.getPaddingBottom() + bottom);
        }
        bar.setBackgroundColor(Color.WHITE);

        final Runnable update = new Runnable() {
            @Override
            public void run() {
                if (bar.getVisibility() == View.GONE) {
                    bar.setTranslationY(0f);
                    if (divider != null) divider.setTranslationY(0f);
                    return;
                }
                int dy = Math.max(0, sv.getScrollY() - bar.getTop());
                bar.setTranslationY((float) dy);
                bar.setElevation(dy > 0 ? (float) dp(2) : 0f);
                if (divider != null) {
                    divider.setTranslationY((float) dy);
                    divider.setElevation(dy > 0 ? (float) dp(2) : 0f);
                }
            }
        };
        sv.getViewTreeObserver().addOnScrollChangedListener(new android.view.ViewTreeObserver.OnScrollChangedListener() {
            @Override
            public void onScrollChanged() {
                update.run();
            }
        });
        // কনটেন্টের উচ্চতা/ট্যাব বদলালে (ট্যাব সুইচ, ডেটা লোড) আবার হিসাব
        content.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                update.run();
            }
        });
    }

    private static final boolean HIDE_TOP_BARS = true;

    private void applyTopBars() {
        if (!HIDE_TOP_BARS) return;
        View bell = findViewById(R.id.bellContainer);
        if (bell != null && bell.getParent() instanceof View) ((View) bell.getParent()).setVisibility(View.GONE);
        View navRow = (View) findViewById(R.id.navProfile).getParent();
        navRow.setVisibility(View.GONE);
        View coverFrame = (View) this.imgCover.getParent();
        if (coverFrame instanceof android.widget.FrameLayout) {
            ImageView back = new ImageView(this);
            back.setImageResource(R.drawable.ic_back);
            back.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
            back.setPadding(dp(8), dp(8), dp(8), dp(8));
            back.setBackground(roundRect(0x66000000, dp(20), 0, 0));
            back.setContentDescription("Back");
            back.setOnClickListener(v -> finish());
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(dp(40), dp(40));
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.topMargin = dp(8);
            lp.leftMargin = dp(10);
            ((android.widget.FrameLayout) coverFrame).addView(back, lp);
        }
    }

    private boolean healing = false;
    private boolean saveRetried = false;

    private void maybeCreateProfile() {
        if (this.isMe && this.profileLoaded && this.usersLoaded && this.profileMissing) {
            this.profileMissing = false;
            healProfile(true, null);
        }
    }

    private void healProfile(final boolean missing, final Runnable after) {
        if (!this.isMe || healing) return;
        healing = true;
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            String name = null;
            String dob = null;
            try {
                JSONObject meta = new JSONObject(r.body).optJSONObject("user_metadata");
                if (meta != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String k : new String[]{"firstName", "middleName", "lastName"}) {
                        String v = meta.optString(k, "").trim();
                        if (!v.isEmpty()) {
                            if (sb.length() > 0) sb.append(' ');
                            sb.append(v);
                        }
                    }
                    if (sb.length() > 0) name = sb.toString();
                    dob = meta.optString("dob", "").trim();
                }
            } catch (Exception ignored) { }
            if (name == null && !missing) { healing = false; return; }
            try {
                JSONObject row = new JSONObject().put("id", this.uid)
                        .put("name", name == null ? "Family Member" : name);
                if (missing) {
                    String b = (dob == null || dob.isEmpty()) ? null : birthdayFromDob(dob);
                    if (b != null) row.put("birthday", b);
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                    f.setTimeZone(TimeZone.getTimeZone("UTC"));
                    row.put("joined_at", f.format(new Date()));
                }
                Db.upsert("profiles", row, err -> {
                    healing = false;
                    if (err != null) { toast(err); return; }
                    if (after != null) after.run(); else loadData();
                });
            } catch (Exception e) {
                healing = false;
            }
        });
    }

    private void render() {
        refreshHomeCard();
        String nameStr;
        if (nz(this.pName)) {
            nameStr = this.pName;
        } else {
            nameStr = this.fallbackName;
            if (nameStr == null) {
                nameStr = "Member";
            }
        }
        this.tvName.setText(nameStr);
        this.pVerifiedBadge = this.pProfSnap != null
                && Boolean.TRUE.equals(this.pProfSnap.child("verifiedBadge").getValue(Boolean.class));
        applyVerifiedBadge();
        applyDefaultAvatar();
        if (nz(this.pUsername)) {
            this.tvUser.setText(userSpan("@", this.pUsername));
            this.tvUser.setAlpha(1.0f);
            this.tvUser.setVisibility(0);
        } else if (this.isMe) {
            this.tvUser.setText(userSpan("@", " Set username"));
            this.tvUser.setAlpha(0.55f);
            this.tvUser.setVisibility(0);
        } else {
            this.tvUser.setVisibility(8);
        }
        this.btnEditUser.setVisibility(this.isMe ? 0 : 8);
        Snap infoSnap = this.pInfo;
        String otherName = infoSnap == null ? null : (String) infoSnap.child("otherName").getValue(String.class);
        if (nz(otherName) && Boolean.TRUE.equals(infoSnap.child("showOtherName").getValue(Boolean.class))) {
            SpannableStringBuilder nb = new SpannableStringBuilder(nameStr);
            int st = nb.length();
            nb.append((CharSequence) " (").append((CharSequence) otherName.trim()).append((CharSequence) ")");
            nb.setSpan(new RelativeSizeSpan(0.55f), st, nb.length(), 33);
            nb.setSpan(new ForegroundColorSpan(Color.parseColor("#65676B")), st, nb.length(), 33);
            this.tvName.setText(nb);
        }
        if (nz(this.pBio)) {
            this.tvBio.setText(this.pBio);
            this.tvBio.setTextColor(Color.parseColor("#1C1E21"));
            this.tvBio.setVisibility(0);
        } else if (this.isMe) {
            this.tvBio.setText("Add bio");
            this.tvBio.setTextColor(Color.parseColor("#65676B"));
            this.tvBio.setVisibility(0);
        } else {
            this.tvBio.setVisibility(8);
        }
        line(this.tvCity, "📍  ", this.pCity, "Add current city");
        boolean hideHome = !this.isMe && this.pHomeLocked;
        line(this.tvHome, "🏠  ", hideHome ? null : this.pHome, "Add hometown");
        if (this.isMe && nz(this.pHome)) {
            android.graphics.drawable.Drawable icon = getResources().getDrawable(
                    this.pHomeLocked ? R.drawable.ic_privacy_lock : R.drawable.ic_globe);
            if (this.pHomeLocked) {
                icon.setBounds(0, 0, dp(16), dp(16));
            } else {
                icon.setColorFilter(Color.parseColor("#65676B"), android.graphics.PorterDuff.Mode.SRC_IN);
                icon.setBounds(0, 0, dp(14), dp(14));
            }
            this.tvHome.setCompoundDrawables(null, null, icon, null);
            this.tvHome.setCompoundDrawablePadding(dp(6));
        } else {
            this.tvHome.setCompoundDrawables(null, null, null, null);
        }
        line(this.tvBirthday, "🎂  ", this.pBirthday, null);
        line(this.tvWork, "💼  ", this.pWork, "Add workplace");
        line(this.tvEdu, "🎓  ", this.pEdu, "Add education");
        if (this.pJoined > 0) {
            this.tvJoined.setText("🕒  Joined " + new SimpleDateFormat("MMMM yyyy", Locale.US).format(new Date(this.pJoined)));
            this.tvJoined.setVisibility(0);
        } else {
            this.tvJoined.setVisibility(8);
        }
        if (this.isMe) {
            this.tvEmail.setText("✉️  " + Me.email() + "  🔒");
            this.tvEmail.setVisibility(0);
            if (this.tvPhone != null) this.tvPhone.setVisibility(8);
            return;
        }
        this.tvEmail.setVisibility(8);
        if (this.tvPhone != null) this.tvPhone.setVisibility(8);
    }

    private boolean nz(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void line(TextView tv, String icon, String value, String placeholderIfMe) {
        tv.setTextColor(Color.parseColor("#1C1E21"));
        if (nz(value)) {
            tv.setText(icon + value);
            tv.setAlpha(1.0f);
            tv.setVisibility(0);
        } else if (!this.isMe || placeholderIfMe == null) {
            tv.setVisibility(8);
        } else {
            tv.setText(icon + placeholderIfMe);
            tv.setAlpha(0.65f);
            tv.setVisibility(0);
        }
    }

    private String compactCount(long n) {
        if (n < 1000) return String.valueOf(n);
        double v = n < 1000000 ? n / 1000.0 : n / 1000000.0;
        String suffix = n < 1000000 ? "K" : "M";
        if (v >= 10) return ((long) v) + suffix;
        String t = String.format(Locale.US, "%.1f", Math.floor(v * 10) / 10);
        if (t.endsWith(".0")) t = t.substring(0, t.length() - 2);
        return t + suffix;
    }

    private void stat(TextView tv, long n, String label) {
        String num = compactCount(n);
        String word = label.toLowerCase(Locale.US);
        if (!word.equals("following") && n != 1) word += "s";
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append((CharSequence) num).append((CharSequence) " ").append((CharSequence) word);
        if (this.isMe) {
            sb.setSpan(new ForegroundColorSpan(Color.parseColor("#1C1E21")), 0, sb.length(), 33);
            sb.setSpan(new StyleSpan(1), 0, num.length(), 33);
        } else {
            // অন্যের প্রোফাইলে সংখ্যা + লেখা দুটোই Black ও Bold
            sb.setSpan(new ForegroundColorSpan(Color.BLACK), 0, sb.length(), 33);
            sb.setSpan(new StyleSpan(1), 0, sb.length(), 33);
        }
        tv.setText(sb);
    }

    private void styleFollow() {
        if (this.btnFollowImg != null && !this.isMe) {
            this.btnFollowImg.setImageResource(this.following ? R.drawable.ic_following : R.drawable.ic_follow_button);
            this.btnFollowImg.setContentDescription(this.following ? "Following" : "Follow");
            this.imgFollowerState.setImageResource(this.followsMe ? R.drawable.ic_follower : R.drawable.ic_non_follower);
        }
    }

    /** Following বাটনে লং প্রেস: Unfollow / Block */
    private static final String FAMILY_CHAT_PKG = "com.familychat.app";

    /** Message বাটন: Family Chat অ্যাপ খুলে সরাসরি এই ব্যক্তির চ্যাটে যাবে */
    private void openFamilyChatWith() {
        String name = (this.pName != null && !this.pName.isEmpty()) ? this.pName : "";
        // ১) Deep link: familychat://chat?uid=...  (Family Chat অ্যাপে এই intent-filter থাকলে সরাসরি চ্যাট খুলবে)
        try {
            Intent dl = new Intent(Intent.ACTION_VIEW, Uri.parse("familychat://chat?uid=" + Uri.encode(this.uid)));
            dl.setPackage(FAMILY_CHAT_PKG);
            dl.putExtra("chat_uid", this.uid);
            dl.putExtra("chat_name", name);
            dl.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(dl);
            return;
        } catch (android.content.ActivityNotFoundException ignored) { }
        // ২) ফলব্যাক: অ্যাপ ওপেন + extras সহ
        Intent intent = getPackageManager().getLaunchIntentForPackage(FAMILY_CHAT_PKG);
        if (intent != null) {
            intent.putExtra("chat_uid", this.uid);
            intent.putExtra("chat_name", name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            toast("Family Chat app is not installed");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + FAMILY_CHAT_PKG)));
            } catch (android.content.ActivityNotFoundException ignored) { }
        }
    }

    private void showFollowMenu() {
        new AlertDialog.Builder(this)
                .setItems(new CharSequence[]{"Unfollow", "Block"}, (d, which) -> {
                    if (which == 0) toggleFollow();
                    else confirmBlockUser();
                })
                .show();
    }

    private void confirmBlockUser() {
        String nm = this.pName != null && !this.pName.isEmpty() ? this.pName : "this user";
        new AlertDialog.Builder(this)
                .setTitle("Block " + nm + "?")
                .setMessage("তিনি আপনাকে সার্চে খুঁজে পাবেন না এবং ফলো করতে পারবেন না। আপনারা একে অপরকে আর ফলো করবেন না।")
                .setPositiveButton("Block", (d, w) -> blockThisUser())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void blockThisUser() {
        if (!Net.check(this)) return;
        final String my = Me.uid();
        final String target = this.uid;
        try {
            String body = new JSONObject().put("blocker_id", my).put("blocked_id", target)
                    .put("blocked_name", (this.pName != null && !this.pName.isEmpty()) ? this.pName : "Family Member")
                    .toString();
            SupabaseRest.call("POST", "/rest/v1/blocked_users?on_conflict=blocker_id,blocked_id", body,
                    "resolution=ignore-duplicates,return=minimal", true, r -> {
                if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                String both = "or=(and(follower_id.eq." + my + ",followee_id.eq." + target + "),"
                        + "and(follower_id.eq." + target + ",followee_id.eq." + my + "))";
                SupabaseRest.call("DELETE", "/rest/v1/follows?" + both, null, "return=minimal", true, x -> { });
                SupabaseRest.call("DELETE", "/rest/v1/notifications?user_id=eq." + target
                        + "&actor_id=eq." + my + "&type=eq.follow", null, null, true, x -> { });
                toast("Blocked");
                finish();
            });
        } catch (Exception e) {
            toast("Could not block");
        }
    }

    private void toggleFollow() {
        if (Net.check(this)) {
            final String my = Me.uid();
            final boolean wasFollowing = this.following;
            final String target = this.uid;
            if (wasFollowing) {
                SupabaseRest.call("DELETE",
                        "/rest/v1/follows?follower_id=eq." + my + "&followee_id=eq." + target,
                        null, "return=minimal", true, r -> {
                    if (!r.ok()) toast(SupabaseRest.errorText(r));
                    loadData();
                });
            } else {
                try {
                    JSONObject row = new JSONObject().put("follower_id", my).put("followee_id", target);
                    SupabaseRest.call("POST", "/rest/v1/follows?on_conflict=follower_id,followee_id",
                            row.toString(), "resolution=merge-duplicates,return=minimal", true, r -> {
                        if (!r.ok()) {
                            toast(SupabaseRest.errorText(r));
                            return;
                        }
                        NotificationHelper.send(target, "follow", null);
                        loadData();
                    });
                } catch (Exception e) {
                    toast("Could not save");
                }
            }
        }
    }

    private void showPeople(final String title, final String node) {
        final boolean followers = "followers".equals(node);
        final String col = followers ? "follower_id" : "followee_id";
        String q = followers
                ? "/rest/v1/follows?followee_id=eq." + this.uid + "&select=follower_id"
                : "/rest/v1/follows?follower_id=eq." + this.uid + "&select=followee_id";
        SupabaseRest.call("GET", q, null, null, true, r -> {
            if (isFinishing()) return;
            if (!r.ok()) {
                toast("Could not load, check your internet");
                return;
            }
            JSONArray a = arrOf(r.body);
            List<String> ids = new ArrayList<>();
            if (a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String id = o.optString(col, "");
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            if (ids.isEmpty()) {
                toast("Nobody here yet");
                return;
            }
            loadNamesAndShow(title, ids);
        });
    }

    private void loadNamesAndShow(final String title, final List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (String id : ids) {
            if (sb.length() > 0) sb.append(',');
            sb.append(id);
        }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + sb + ")&select=id,name", null, null, true, r -> {
            if (isFinishing()) return;
            Map<String, String> names = new HashMap<>();
            JSONArray a = arrOf(r.body);
            if (r.ok() && a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) names.put(o.optString("id", ""), o.optString("name", ""));
                }
            }
            final String[] labels = new String[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                String n = names.get(ids.get(i));
                labels[i] = (n == null || n.isEmpty()) ? "Member" : n;
            }
            new AlertDialog.Builder(this).setTitle(title).setItems(labels, (d, which) ->
                    open(ProfileActivity.this, ids.get(which), labels[which])).show();
        });
    }

    private GradientDrawable roundRect(int fill, int radius, int strokeColor, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    // বায়ো এডিট: পুরনো ডায়ালগ বাদ। এখন Profile info -> Intro-র Bio edit স্ক্রিনই খোলে।
    private void openBioEditor() {
        if (!this.isMe) return;
        String privacy = this.pBioPrivacy;
        Intent intent = new Intent(ProfileActivity.this, BioEditActivity.class);
        intent.putExtra("uid", this.uid);
        intent.putExtra("bio", this.pBio == null ? "" : this.pBio);
        if (privacy != null) intent.putExtra("privacy", privacy);
        startActivity(intent); // ফিরে এলে onResume() -> loadData() নতুন বায়ো দেখায়
    }

    private void editSingle(String title, String current, int max, final String field) {
        if (Net.check(this)) {
            final EditText et = new EditText(this);
            et.setText(current == null ? "" : current);
            et.setSelection(et.getText().length());
            et.setTextColor(Color.parseColor("#1C1E21"));
            et.setSingleLine(false);
            et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
            et.setPadding(dp(20), dp(16), dp(20), dp(16));
            new AlertDialog.Builder(this).setTitle(title).setView(et).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    String t = et.getText().toString().trim();
                    if (!field.equals("name") || !t.isEmpty()) {
                        saveProfileField(field, t.isEmpty() ? null : t);
                    }
                }
            }).setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).show();
        }
    }

    private void editUsername() {
        if (Net.check(this)) {
            final EditText et = new EditText(this);
            et.setHint("username (a-z, 0-9, . _)");
            et.setTextColor(Color.parseColor("#1C1E21"));
            String str = this.pUsername;
            if (str == null) {
                str = "";
            }
            et.setText(str);
            et.setSingleLine(true);
            et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
            et.setPadding(dp(20), dp(16), dp(20), dp(16));
            new AlertDialog.Builder(this).setTitle("Username").setView(et).setPositiveButton("Save", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialogInterface, int i) {
                    saveUsername(et.getText().toString().trim().toLowerCase(Locale.US));
                }
            }).setNegativeButton("Cancel", (DialogInterface.OnClickListener) null).show();
        }
    }

    private CharSequence userSpan(String at, String name) {
        SpannableStringBuilder sb = new SpannableStringBuilder(at + name);
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#0866FF")), 0, at.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#1C1E21")), at.length(), sb.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void pick(String target) {
        this.pickTarget = target;
        this.picker.launch("image/*");
    }

    private void saveProfileField(final String column, final String value) {
        try {
            JSONObject f = new JSONObject().put(column, value == null ? JSONObject.NULL : value);
            SupabaseRest.call("PATCH", "/rest/v1/profiles?id=eq." + Me.uid(), f.toString(),
                    "return=representation", true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) { toast(SupabaseRest.errorText(r)); return; }
                JSONArray a = arrOf(r.body);
                if (a == null || a.length() == 0) {
                    if (!saveRetried) {
                        saveRetried = true;
                        healProfile(true, () -> saveProfileField(column, value));
                    } else {
                        toast("Profile not saved, please try again");
                    }
                    return;
                }
                saveRetried = false;
                loadData();
            });
        } catch (Exception e) {
            toast("Could not save");
        }
    }

    private void saveUsername(final String u) {
        if (!u.matches("[a-z0-9._]{3,20}")) {
            toast("3-20 characters: a-z, 0-9, . and _ only");
        } else if (!u.equals(this.pUsername) && Net.check(this)) {
            SupabaseRest.call("GET", "/rest/v1/profiles?username=eq." + u + "&select=id&limit=1",
                    null, null, true, r -> {
                if (isFinishing()) return;
                if (!r.ok()) {
                    toast(SupabaseRest.errorText(r));
                    return;
                }
                JSONArray a = arrOf(r.body);
                if (a != null && a.length() > 0) {
                    JSONObject o = a.optJSONObject(0);
                    String id = o == null ? "" : o.optString("id", "");
                    if (!id.isEmpty() && !id.equals(Me.uid())) {
                        toast("This username is already taken, try another");
                        return;
                    }
                }
                saveProfileField("username", u);
            });
        }
    }

    private void saveCroppedImage(final String path) {
        final Bitmap bitmap = BitmapFactory.decodeFile(path);
        if (bitmap == null) {
            toast("Failed to load image");
            return;
        }
        final String target = this.pickTarget;
        final String column = "avatar".equals(target) ? "avatar_key" : "cover_key";
        ImageView preview = new ImageView(this);
        preview.setImageBitmap(bitmap);
        preview.setAdjustViewBounds(true);
        preview.setPadding(dp(16), dp(16), dp(16), dp(16));
        new AlertDialog.Builder(this)
                .setTitle("ছবি সেভ করবেন?")
                .setView(preview)
                .setPositiveButton("Save", (d, w) -> uploadPhoto(bitmap, target, column))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void uploadPhoto(Bitmap bitmap, final String target, final String column) {
        if (!Net.check(this)) return;
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
            toast("Uploading photo...");
            Media.upload(baos.toByteArray(), target, target + ".jpg", "image/jpeg", (key, err) -> {
                if (err != null) {
                    toast(err);
                    return;
                }
                showLocalPhoto(bitmap, target);
                ProfileMedia.record(target, key, () -> saveProfileField(column, key));
                if ("avatar".equals(target)) {
                    AvatarLoader.setKey(this.uid, key);
                    if (this.isMe) AccountPhoto.refresh(this);      // লগইন পেজের কার্ডের ছবিও নতুন হবে
                }
                // নিউজ ফিড/প্রোফাইলে পোস্ট + ফলোয়ার ও ফ্রেন্ডদের নোটিফিকেশন
                if (this.isMe) ProfilePhotoPost.announce(target, key, () -> { if (!isFinishing()) loadData(); });
            });
        } catch (Throwable e) {
            toast("Could not save photo");
        }
    }

    private void showLocalPhoto(Bitmap bm, String target) {
        if ("avatar".equals(target)) {
            RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(getResources(), bm);
            d.setCircular(true);
            this.imgAvatar.setImageDrawable(d);
            this.hasAvatarPhoto = true;
        } else {
            this.imgCover.setImageBitmap(bm);
        }
    }

    private void loadPhoto(String key, final boolean cover) {
        Media.loadBitmap(key, bm -> {
            if (isFinishing()) return;
            showLocalPhoto(bm, cover ? "cover" : "avatar");
        }, (v, err) -> {
            if (this.isMe && !isFinishing()) toast(err);
        });
    }

    private void confirmLogout() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(64, 24, 64, 0);

        TextView msg = new TextView(this);
        msg.setText("Are you sure you want to log out?");
        msg.setTextColor(Color.parseColor("#1C1E21"));
        msg.setTextSize(16);
        box.addView(msg);

        CheckBox save = new CheckBox(this);
        save.setText("Save login information");
        save.setTextColor(Color.parseColor("#1C1E21"));
        save.setChecked(true);
        box.addView(save);

        TextView hint = new TextView(this);
        hint.setText("Next time just tap your profile on the login page to sign in.");
        hint.setTextColor(Color.parseColor("#65676B"));
        hint.setTextSize(13);
        box.addView(hint);

        new AlertDialog.Builder(this)
                .setTitle("Log out")
                .setView(box)
                .setPositiveButton("Log out", (d, w) -> {
                    String u = SupabaseRest.uid();
                    if (save.isChecked()) {
                        boolean ok = SecureStore.commitCard(this, u, SupabaseRest.email(), myName);
                        if (!ok) Toast.makeText(this, "Login information could not be saved", Toast.LENGTH_LONG).show();
                    } else {
                        SecureStore.discardLogin(this, u);
                    }
                    AuthRepo.logout(err -> goLogin());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void goLogin() {
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void soon(String feature) {
        TopToast.show(this, feature + " coming soon");
    }

    // Profile info পেন্সিল আইকনে ক্লিক করলে নতুন ProfileInfoEditActivity ওপেন হবে
    private void editDetails() {
        if (this.isMe) {
            Intent intent = new Intent(ProfileActivity.this, ProfileInfoEditActivity.class);
            intent.putExtra("uid", this.uid);
            startActivity(intent);
        }
    }

    private final class InfoCardView {
        String key;
        LinearLayout box;
        TextView label, badge, addr, hint, privacyText;
        ImageView infoBtn, privacyIcon;
    }

    private static final String[][] EXTRA_CARDS = {
            {"blood", "Blood group:", "ic_blood_group"},
            {"gender", "Gender:", "ic_gender_info"},
            {"relationship", "Relationship:", "ic_relationship"},
            {"language", "Languages:", "ic_languages"},
            {"phones", "Cellphone:", "ic_cellphone"},
            {"email", "Email:", "ic_email_info"},
            {"socials", "Social:", "ic_social_info"},
            {"family", "Family:", "ic_add_family"},
            {"school", "School/Madrasah:", "ic_school"},
            {"college", "College/University:", "ic_college"}
    };

    private static final boolean HOME_ALWAYS_VERIFIED = true;
    private final Map<String, InfoCardView> cards = new java.util.LinkedHashMap<>();
    private LinearLayout pinnedBox, moreCardsBox;
    private TextView cardsSeeMore;
    private boolean cardsExpanded;
    private String pPinKey;
    private Snap pCitySnap;
    private String pCitySummary;
    private boolean bUpdated, bLocked, bShowYear, bIdSubmitted;
    private String bDate, bFullName;
    private boolean pCityLocked, cFilled;
    private String nNumber, nStatus;
    private boolean nLocked = true, nFilled, nidCleaned, pNidVerified;

    private int resId(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        return id != 0 ? id : R.drawable.ic_profile;
    }

    // ---- Personal details (max 2 rows) + Followers strip ----
    private LinearLayout infoRows, followerStrip;
    private LinearLayout followersSection;
    private TextView tvAddInfo, tvNoFollowers;
    private int followerCount;

    private View composerRow;
    private String composerName;

    /** photo = true হলে নতুন-পোস্ট স্ক্রিন খুলেই গ্যালারি খোলে (হোম পেজের মতো) */
    private void openComposer(boolean photo) {
        startActivity(new Intent(this, NewPostActivity.class)
                .putExtra("name", composerName)
                .putExtra("pickPhoto", photo));
    }

    private void ensureHomeCard() {
        if (homeCard != null) return;
        View editBtn = findViewById(R.id.btnEditDetails);
        if (editBtn == null) return;
        View headRow = (View) editBtn.getParent();
        ViewGroup col = (ViewGroup) headRow.getParent();
        int headIndex = col.indexOfChild(headRow);

        // pencil: detach from the old "Profile info :" row, re-attach at the far right of "Personal details"
        ViewGroup.LayoutParams old = editBtn.getLayoutParams();
        int bw = old != null && old.width > 0 ? old.width : dp(28);
        int bh = old != null && old.height > 0 ? old.height : dp(28);
        ((ViewGroup) editBtn.getParent()).removeView(editBtn);
        col.removeView(headRow);

        homeCard = new LinearLayout(this);
        homeCard.setOrientation(LinearLayout.VERTICAL);
        homeCard.setBackground(null);   // no card / border
        homeCard.setPadding(dp(14), dp(10), dp(14), dp(6));

        // Personal details + Pin details + Followers — এই বক্স শুধু All ট্যাবে দেখায়
        detailsBox = new LinearLayout(this);
        detailsBox.setOrientation(LinearLayout.VERTICAL);
        homeCard.addView(detailsBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        detailsBox.setVisibility(curTab == ProfileMedia.TAB_ALL ? View.VISIBLE : View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("Personal details");
        title.setTextColor(Color.parseColor("#050505"));
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(editBtn, new LinearLayout.LayoutParams(bw, bh));
        detailsBox.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        infoRows = new LinearLayout(this);
        infoRows.setOrientation(LinearLayout.VERTICAL);
        detailsBox.addView(infoRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        infoMore = new LinearLayout(this);
        infoMore.setOrientation(LinearLayout.VERTICAL);
        infoMore.setVisibility(View.GONE);
        detailsBox.addView(infoMore, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvInfoMore = new TextView(this);
        tvInfoMore.setText("See more details");
        tvInfoMore.setTextColor(Color.parseColor("#0866FF"));
        tvInfoMore.setTextSize(14);
        tvInfoMore.setTypeface(null, android.graphics.Typeface.BOLD);
        tvInfoMore.setPadding(0, dp(6), 0, dp(6));
        tvInfoMore.setVisibility(View.GONE);
        tvInfoMore.setOnClickListener(v -> {
            infoMoreOpen = !infoMoreOpen;
            infoMore.setVisibility(infoMoreOpen ? View.VISIBLE : View.GONE);
            tvInfoMore.setText(infoMoreOpen ? "See less" : "See more details");
        });
        detailsBox.addView(tvInfoMore);

        // Pinned details (Profile details-এর পরে)
        pinnedRows = new LinearLayout(this);
        pinnedRows.setOrientation(LinearLayout.VERTICAL);
        detailsBox.addView(pinnedRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvAddInfo = new TextView(this);
        tvAddInfo.setText("Add info");
        tvAddInfo.setTextColor(Color.parseColor("#0866FF"));
        tvAddInfo.setTextSize(14);
        tvAddInfo.setPadding(0, dp(8), 0, dp(8));
        tvAddInfo.setVisibility(View.GONE);
        tvAddInfo.setOnClickListener(v -> editDetails());
        detailsBox.addView(tvAddInfo);

        // ---- Followers ----
        followersSection = new LinearLayout(this);
        followersSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout fHead = new LinearLayout(this);
        fHead.setOrientation(LinearLayout.HORIZONTAL);
        fHead.setGravity(Gravity.CENTER_VERTICAL);
        TextView fTitle = new TextView(this);
        fTitle.setText("Followers");
        fTitle.setTextColor(Color.parseColor("#050505"));
        fTitle.setTextSize(19);
        fTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        fHead.addView(fTitle, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView seeMore = new TextView(this);
        seeMore.setText("See more");
        seeMore.setTextColor(Color.parseColor("#0866FF"));
        seeMore.setTextSize(14);
        seeMore.setPadding(dp(8), dp(6), 0, dp(6));
        seeMore.setOnClickListener(v -> startActivity(new Intent(this, MembersActivity.class)));
        fHead.addView(seeMore);
        followersSection.addView(fHead, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        followerStrip = new LinearLayout(this);
        followerStrip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stripLp.topMargin = dp(8);
        followersSection.addView(followerStrip, stripLp);

        tvNoFollowers = new TextView(this);
        tvNoFollowers.setText("No followers yet");
        tvNoFollowers.setTextColor(Color.parseColor("#65676B"));
        tvNoFollowers.setTextSize(13);
        tvNoFollowers.setPadding(0, dp(6), 0, dp(6));
        tvNoFollowers.setVisibility(View.GONE);
        followersSection.addView(tvNoFollowers);

        LinearLayout.LayoutParams fsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fsLp.topMargin = dp(14);
        detailsBox.addView(followersSection, fsLp);

        // Followers-এর একদম নিচে: হোম পেজের মতোই পোস্ট বক্স (নিজের প্রোফাইলে)।
        // পোস্টের পুরো লজিক NewPostActivity-তে — হোম পেজে যেভাবে চলে এখানেও হুবহু সেভাবেই চলে, নিউজ ফিডেও যায়।
        composerRow = LayoutInflater.from(this).inflate(R.layout.item_profile_composer, homeCard, false);
        composerRow.setVisibility(this.isMe && curTab != ProfileMedia.TAB_SHORTS ? View.VISIBLE : View.GONE);
        if (this.isMe) {
            AvatarLoader.load((ImageView) composerRow.findViewById(R.id.imgComposerAvatar), Me.uid());
            PostOps.loadMyName(Me.uid(), n -> composerName = n);
        }
        composerRow.findViewById(R.id.tvComposerPill).setOnClickListener(v -> openComposer(false));
        composerRow.findViewById(R.id.btnComposerImage).setOnClickListener(v -> openComposer(true));
        homeCard.addView(composerRow);

        col.addView(homeCard, headIndex, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** One plain row: icon + text (no card, no privacy icon). */
    private View buildInfoRow(String iconName, String text, boolean verified) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(5), 0, dp(5));

        ImageView ic = new ImageView(this);
        ic.setImageResource(resId(iconName));
        ic.setColorFilter(Color.parseColor("#1C1E21"), android.graphics.PorterDuff.Mode.SRC_IN);
        row.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#050505"));
        tv.setTextSize(15);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.setMarginStart(dp(12));
        row.addView(tv, tp);

        if (verified) {
            TextView b = new TextView(this);
            styleBadge(b, "Verified", 1);
            b.setTextSize(10);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setPadding(dp(6), dp(2), dp(6), dp(2));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.setMarginStart(dp(8));
            row.addView(b, bp);
        }
        return row;
    }

    /** Called with the rows of `follows?followee_id=uid`; shows up to 4 followers. */
    private void loadFollowerStrip(JSONArray follows) {
        ensureHomeCard();
        if (homeCard == null) return;
        followerCount = follows == null ? 0 : follows.length();
        followerStrip.removeAllViews();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; follows != null && i < follows.length() && ids.size() < 4; i++) {
            JSONObject o = follows.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("follower_id", "");
            if (!id.isEmpty()) ids.add(id);
        }
        if (ids.isEmpty()) {
            tvNoFollowers.setVisibility(View.VISIBLE);
            followersSection.setVisibility(this.isMe ? View.VISIBLE : View.GONE);
            return;
        }
        followersSection.setVisibility(View.VISIBLE);
        tvNoFollowers.setVisibility(View.GONE);
        StringBuilder sb = new StringBuilder();
        for (String id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id); }
        SupabaseRest.call("GET", "/rest/v1/profiles?id=in.(" + sb + ")&select=id,name,info", null, null, true, r -> {
            if (isFinishing()) return;
            Map<String, JSONObject> byId = new HashMap<>();
            JSONArray a = arrOf(r.body);
            if (r.ok() && a != null) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o != null) byId.put(o.optString("id", ""), o);
                }
            }
            followerStrip.removeAllViews();
            for (String id : ids) {
                JSONObject o = byId.get(id);
                String name = o == null ? "" : o.optString("name", "").trim();
                String g = null;
                if (o != null && o.optJSONObject("info") != null) g = o.optJSONObject("info").optString("gender", null);
                followerStrip.addView(buildFollowerItem(id, name.isEmpty() ? "Member" : name, g));
            }
        });
    }

    private View buildFollowerItem(final String id, final String name, String gender) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setLayoutParams(new LinearLayout.LayoutParams(dp(70), LinearLayout.LayoutParams.WRAP_CONTENT));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        AvatarLoader.load(iv, id);     // আসল প্রোফাইল ছবি (না থাকলে জেন্ডার অনুযায়ী ডিফল্ট)
        item.addView(iv, new LinearLayout.LayoutParams(dp(54), dp(54)));

        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextColor(Color.parseColor("#050505"));
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        item.addView(tv, tp);

        item.setOnClickListener(v -> ProfileActivity.open(this, id, name));
        return item;
    }

    private int homeCardWidth() {
        return getResources().getDisplayMetrics().widthPixels * 57 / 100;
    }

    private void fitCompactText(TextView tv, String text) {
        final float maxSp = 12f, minSp = 7f;
        float availPx = homeCardWidth() - dp(10 + 10 + 2) - dp(4);
        android.graphics.Paint p = new android.graphics.Paint(tv.getPaint());
        float sp = maxSp;
        for (; sp > minSp; sp -= 0.5f) {
            p.setTextSize(android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics()));
            float longest = 0f;
            for (String line : text.split("\\n")) longest = Math.max(longest, p.measureText(line));
            if (longest <= availPx) break;
        }
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setText(text);
    }

    private String compactAddressOf(Snap h, String fallback) {
        if (h == null || !h.exists()) return nz(fallback) ? fallback : "";
        String div = h.child("division").getValue(String.class);
        String dist = h.child("district").getValue(String.class);
        String up = h.child("upazila").getValue(String.class);
        String po = h.child("postOffice").getValue(String.class);
        String code = h.child("postCode").getValue(String.class);
        String vil = h.child("village").getValue(String.class);
        String ward = h.child("ward").getValue(String.class);
        StringBuilder sb = new StringBuilder();
        if (nz(div)) sb.append("Division : ").append(div.trim()).append(".\n");
        if (nz(dist)) sb.append("Districts : ").append(dist.trim()).append(".\n");
        if (nz(up)) sb.append("Thana/Upozila : ").append(up.trim()).append("\n");
        if (nz(po)) {
            sb.append("Post Office-Box : ").append(po.trim());
            if (nz(code)) sb.append("-").append(code.trim());
            sb.append("\n");
        }
        if (nz(ward) || nz(vil)) {
            if (nz(ward)) sb.append("Ward : ").append(ward.trim());
            if (nz(vil)) {
                if (nz(ward)) sb.append("  ");
                sb.append("Vill : ").append(vil.trim());
            }
            sb.append("\n");
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? (nz(fallback) ? fallback : "") : out;
    }

    private void styleBadge(TextView b, String text, int type) {
        if (b == null) return;
        int fg = type == 1 ? Color.parseColor("#15803D") : (type == 2 ? Color.parseColor("#B45309") : Color.parseColor("#B91C1C"));
        int bg = type == 1 ? Color.parseColor("#DCFCE7") : (type == 2 ? Color.parseColor("#FEF3C7") : Color.parseColor("#FEE2E2"));
        b.setText(text);
        b.setTextColor(fg);
        b.setBackground(roundRect(bg, dp(4), 0, 0));
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private void showTab(int t) {
        this.curTab = t;
        refreshStatusList();   // ট্যাব অনুযায়ী লিস্ট বদলায় (All = পুরো টাইমলাইন, Status = শুধু টেক্সট)
        applyFeedVisibility();
    }

    private void applyFeedVisibility() {
        boolean show = !cardsExpanded;
        View tabRow = (View) tabAll.getParent();
        if (tabRow.getParent() instanceof android.widget.HorizontalScrollView) {
            tabRow = (View) tabRow.getParent();
        }
        tabRow.setVisibility(show ? View.VISIBLE : View.GONE);
        // All: Personal details + Pin details + Followers + টাইমলাইন পোস্ট (Profile/Cover photo update সহ)
        // Status: শুধু টেক্সট পোস্ট; Shorts: Shorts; Photos: আলাদা পেজ
        boolean postsTab = curTab == ProfileMedia.TAB_POSTS;
        boolean allTab = curTab == ProfileMedia.TAB_ALL;
        // All ট্যাবে Personal details/Followers-এর নিচে টাইমলাইনের পোস্ট + Profile/Cover photo update দেখায়
        boolean listTab = postsTab || allTab;
        View rvPosts = findViewById(R.id.rvProfilePosts);
        if (rvPosts != null) rvPosts.setVisibility(show && listTab ? View.VISIBLE : View.GONE);
        // "Say good or silent." পোস্ট বক্স Shorts ট্যাবে থাকবে না
        if (composerRow != null) {
            composerRow.setVisibility(this.isMe && curTab != ProfileMedia.TAB_SHORTS ? View.VISIBLE : View.GONE);
        }
        if (detailsBox != null) detailsBox.setVisibility(show && curTab == ProfileMedia.TAB_ALL ? View.VISIBLE : View.GONE);
        if (shorts != null) {
            shorts.setOwner(this.isMe, this.uid);
            shorts.setVisible(show && curTab == ProfileMedia.TAB_SHORTS);
        }
        tvNoPosts.setVisibility(show && listTab && postsEmpty ? View.VISIBLE : View.GONE);
    }

    /** Status ট্যাবে শুধু টেক্সট পোস্ট (ছবি-ওয়ালা ও শেয়ার করা পোস্ট বাদ) */
    private List<Post> textOnly(List<Post> src) {
        List<Post> out = new ArrayList<>();
        for (Post p : src) {
            if (!PostImage.known(p.id)) continue;                       // ছবির খবর আসা পর্যন্ত অপেক্ষা
            if (PostImage.get(p.id) != null) continue;                  // ছবি আছে → Photos-এ যাবে
            if (p.sharedPostId != null && !p.sharedPostId.isEmpty()) continue;
            out.add(p);
        }
        return out;
    }

    private void refreshStatusList() {
        if (adapter == null || isFinishing()) return;
        // All ট্যাব: সব টাইমলাইন পোস্ট (ছবি, শেয়ার, Profile Photo / Cover Photo update সহ)
        // Status ট্যাব: শুধু টেক্সট পোস্ট
        List<Post> shown = curTab == ProfileMedia.TAB_ALL ? new ArrayList<>(allPosts) : textOnly(allPosts);
        this.adapter.setItems(shown);
        this.postsEmpty = shown.isEmpty();
        applyFeedVisibility();
    }

    // নতুন Profile info কার্ড: (key, label, icon)
    private static final String[][] PI_CARDS = {
            {"pi_city", "Current city:", "ic_current_city"},
            {"pi_home", "Hometown:", "ic_hometown"},
            {"pi_birthday", "Birthday:", "ic_birthday_info"},
            {"pi_gender", "Gender:", "ic_gender_info"},
            {"pi_lang", "Languages:", "ic_languages"}
    };
    private static final int HIGHLIGHT_COUNT = 3;   // Profile details: প্রথম ৩টা, বাকিগুলো See more-এ
    private LinearLayout infoMore, pinnedRows;
    private TextView tvInfoMore;
    private boolean infoMoreOpen;
    private List<PinnedItems.Item> pinnedAll = new ArrayList<>();
    private List<String> pinnedKeys = new ArrayList<>();
    private static final String[] PI_MONTHS = {"Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    private int piTotal;
    private boolean piRegFetched;
    private String piRegDob = "", piRegGender = "";

    private static boolean piPub(JSONObject priv, String k) {
        return priv == null || priv.optBoolean(k, true);
    }

    private static boolean validDob(String d) {
        return d != null && d.matches("\\d{1,2}/\\d{1,2}/\\d{4}");
    }

    /** 10/10/1996 → 10th Oct 1996 (withYear=false হলে 10th Oct) */
    private static String prettyDob(String d, boolean withYear) {
        try {
            String[] p = d.split("/");
            int day = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            String suf = (day >= 11 && day <= 13) ? "th"
                    : day % 10 == 1 ? "st" : day % 10 == 2 ? "nd" : day % 10 == 3 ? "rd" : "th";
            return day + suf + " " + PI_MONTHS[m - 1] + (withYear ? " " + p[2] : "");
        } catch (Exception e) {
            return d;
        }
    }

    /** রেজিস্ট্রেশনের জন্মতারিখ/জেন্ডার profiles.info-তে না থাকলে (শুধু নিজের প্রোফাইলে) auth metadata থেকে আনে */
    private void fetchRegistrationInfo() {
        if (piRegFetched || !this.isMe) return;
        piRegFetched = true;
        SupabaseRest.call("GET", "/auth/v1/user", null, null, true, r -> {
            if (isFinishing()) return;
            try {
                JSONObject meta = new JSONObject(r.body).optJSONObject("user_metadata");
                if (meta != null) {
                    piRegDob = meta.optString("dob", "").trim();
                    piRegGender = meta.optString("gender", "").trim();
                    refreshHomeCard();
                }
            } catch (Exception ignored) { }
        });
    }

    private void refreshHomeCard() {
        ensureHomeCard();
        if (homeCard == null) return;

        JSONObject info = this.pProfSnap == null ? new JSONObject() : this.pProfSnap.child("info").json();
        JSONObject priv = info.optJSONObject("priv");

        String city = info.optString("currentCity", "").trim();
        String home = info.optString("hometown", "").trim();
        String gender = info.optString("gender", "").trim();
        String dob = info.optString("dob", "").trim();
        if (!validDob(dob)) dob = piRegDob;
        if (gender.isEmpty()) gender = piRegGender;
        if ((!validDob(dob) || gender.isEmpty()) && this.pProfSnap != null) fetchRegistrationInfo();

        StringBuilder lang = new StringBuilder();
        JSONArray la = info.optJSONArray("languages");
        if (la != null) {
            for (int i = 0; i < la.length(); i++) {
                String l = la.optString(i, "").trim();
                if (l.isEmpty()) continue;
                if (lang.length() > 0) lang.append(", ");
                lang.append(l);
            }
        }

        // Others see only Public items; the owner sees everything. No privacy icons here (edit page only).
        boolean bdayPub = piPub(priv, "birthday");
        boolean yearPub = piPub(priv, "birthYear");
        String bdayText = validDob(dob) ? prettyDob(dob, this.isMe || yearPub) : "";

        // Relationship (পাবলিক ডিফল্ট false) ও Family Members — Personal info-র মতোই
        String relText = info.optString("relationship", "").trim();
        JSONObject relWith = info.optJSONObject("relationshipWith");
        if (!relText.isEmpty() && relWith != null && !relWith.optString("name", "").isEmpty()) {
            relText = relText + " to " + relWith.optString("name");
        }
        boolean relPub = priv != null && priv.optBoolean("relationship", false);

        String[] famKeys = {"father", "mother", "sister", "brother", "spouse", "son"};
        String[] famLabels = {"Father", "Mother", "Sister", "Brother", "Spouse", "Son"};
        JSONObject fam = info.optJSONObject("familyMembers");
        JSONObject famPriv = fam == null ? null : fam.optJSONObject("priv");
        StringBuilder famText = new StringBuilder();
        boolean famPub = false;
        for (int i = 0; fam != null && i < famKeys.length; i++) {
            String v = fam.optString(famKeys[i], "").trim();
            boolean kp = famPriv != null && famPriv.optBoolean(famKeys[i], false);
            if (kp) famPub = true;
            if (v.isEmpty() || !(this.isMe || kp)) continue;
            String lb = famLabels[i];
            if ("spouse".equals(famKeys[i]) && !fam.optString("spouseLabel", "").isEmpty()) {
                lb = fam.optString("spouseLabel", "");
            }
            if (famText.length() > 0) famText.append("\n");
            famText.append(lb).append(": ").append(v);
        }

        String[] order = {"pi_city", "pi_home", "pi_birthday", "pi_rel", "pi_family", "pi_gender", "pi_lang"};
        String[] icons = {"ic_current_city", "ic_hometown", "ic_birthday_info", "ic_relationship",
                "ic_add_family", "ic_gender_info", "ic_languages"};
        String[] text = {city, home, bdayText, relText, famText.toString(), gender, lang.toString()};
        boolean[] pub = {piPub(priv, "currentCity"), piPub(priv, "hometown"), bdayPub, relPub, famPub,
                piPub(priv, "gender"), piPub(priv, "languages")};

        infoRows.removeAllViews();
        infoMore.removeAllViews();
        int shown = 0;
        for (int i = 0; i < order.length; i++) {
            if (!nz(text[i]) || !(this.isMe || pub[i])) continue;
            boolean ver = "pi_birthday".equals(order[i]) && info.optBoolean("nidVerified", false);
            View rowV = buildInfoRow(icons[i], text[i], ver);
            if (shown < HIGHLIGHT_COUNT) infoRows.addView(rowV); else infoMore.addView(rowV);
            shown++;
        }
        boolean hasMore = infoMore.getChildCount() > 0;
        tvInfoMore.setVisibility(hasMore ? View.VISIBLE : View.GONE);
        infoMore.setVisibility(hasMore && infoMoreOpen ? View.VISIBLE : View.GONE);
        tvInfoMore.setText(infoMoreOpen ? "See less" : "See more details");
        renderPinned();
        piTotal = shown;
        cardsExpanded = false;
        tvAddInfo.setVisibility(shown == 0 && this.isMe ? View.VISIBLE : View.GONE);
        applyFeedVisibility();
    }

    /** Intro > Pinned details-এ বেছে নেওয়া তথ্য (সর্বোচ্চ ৫টি), Profile details-এর নিচে */
    private void loadPinned() {
        if (this.uid == null) return;
        PinnedItems.load(this.uid, this.isMe, (all, pinned) -> {
            if (isFinishing()) return;
            this.pinnedAll = all;
            this.pinnedKeys = pinned;
            ensureHomeCard();
            renderPinned();
        });
    }

    // লেআউট: res/layout/item_pinned_section_head.xml, item_pinned_row.xml (সাইজ সেখানেই বদলাবেন)
    private void renderPinned() {
        if (pinnedRows == null) return;
        pinnedRows.removeAllViews();
        LayoutInflater inf = LayoutInflater.from(this);
        int count = 0;
        for (String k : pinnedKeys) {
            if (count >= PinnedItems.MAX) break;
            PinnedItems.Item it = PinnedItems.find(pinnedAll, k);
            if (it == null) continue;
            count++;

            View head = inf.inflate(R.layout.item_pinned_section_head, pinnedRows, false);
            ((TextView) head.findViewById(R.id.tvTitle)).setText(it.label);
            View pen = head.findViewById(R.id.ivEdit);
            if (this.isMe) pen.setOnClickListener(v -> editDetails());
            else pen.setVisibility(View.GONE);
            pinnedRows.addView(head);

            for (int i = 0; i < it.lines.size(); i++) {
                View row = inf.inflate(R.layout.item_pinned_row, pinnedRows, false);
                String lineIcon = i < it.icons.size() ? it.icons.get(i) : it.icon;
                ((ImageView) row.findViewById(R.id.ivIcon)).setImageResource(resId(lineIcon));
                ((TextView) row.findViewById(R.id.tvTitle)).setText(it.lines.get(i));
                String sub = i < it.subs.size() ? it.subs.get(i) : "";
                if (sub != null && !sub.isEmpty()) {
                    TextView st = row.findViewById(R.id.tvSub);
                    st.setText(sub);
                    st.setVisibility(View.VISIBLE);
                }
                pinnedRows.addView(row);
            }
        }
    }

    private void setPrivacyIcon(InfoCardView v, boolean locked, boolean show) {
        v.privacyIcon.setVisibility(show ? View.VISIBLE : View.GONE);
        v.privacyIcon.setImageResource(locked ? R.drawable.ic_privacy_lock : R.drawable.ic_globe);
        if (locked) v.privacyIcon.clearColorFilter();
        else v.privacyIcon.setColorFilter(Color.parseColor("#65676B"), android.graphics.PorterDuff.Mode.SRC_IN);
    }

    private void fitOrWrap(TextView tv, Snap snap, String summary) {
        String t = compactAddressOf(snap, summary);
        if (snap != null && snap.exists() && t.contains(" : ")) {
            fitCompactText(tv, t);
        } else {
            tv.setTextSize(12);
            tv.setText(t);
        }
    }

    private void togglePrivacyOf(final String key) {
        final String table;
        final String column;
        final boolean nowLocked;
        switch (key) {
            case "city":     table = "city_info";  column = "city_privacy";  nowLocked = !this.pCityLocked; break;
            case "nid":      table = "nid_info";   column = "privacy";       nowLocked = !this.nLocked;     break;
            case "birthday": table = "birth_info"; column = "privacy";       nowLocked = !this.bLocked;     break;
            default:         table = "home_info";  column = "home_privacy";  nowLocked = !this.pHomeLocked; break;
        }
        try {
            JSONObject f = new JSONObject().put(column, nowLocked ? "locked" : "public");
            Db.patch(table, "user_id=eq." + this.uid, f, err -> {
                if (err != null) {
                    toast(err);
                    return;
                }
                if ("city".equals(key)) this.pCityLocked = nowLocked;
                else if ("nid".equals(key)) this.nLocked = nowLocked;
                else if ("birthday".equals(key)) this.bLocked = nowLocked;
                else this.pHomeLocked = nowLocked;
                refreshHomeCard();
                toast(nowLocked ? "Locked: only you can see this" : "Public: everyone can see this");
            });
        } catch (Exception e) {
            toast("Could not save");
        }
    }

    private void renderInfo() {
    }

    private void toggleLike(Post p) {
        final String me = SupabaseRest.uid();
        if (p == null || me == null || p.id == null || !Net.check(this)) return;
        if (p.likedByMe) {
            ReactionKit.removeReaction(this, p.id, me);
            p.likedByMe = false;
            p.myReaction = null;
            p.likes = Math.max(0, p.likes - 1);
        } else {
            ReactionKit.setReaction(this, p.id, me, "like");
            Sfx.like();
            NotificationHelper.send(p.uid, "like", p.id);
            p.likedByMe = true;
            p.myReaction = "like";
            p.likes = p.likes + 1;
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private Bitmap decodeB64(String b64) {
        if (b64 == null) {
            return null;
        }
        try {
            byte[] decoded = Base64.decode(b64, 0);
            return BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void toast(String msg) {
        TopToast.show(this, msg);
    }
}