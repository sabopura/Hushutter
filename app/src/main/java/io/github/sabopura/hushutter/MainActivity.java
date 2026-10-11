package io.github.sabopura.hushutter;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * ホーム（シャッター音の切りかえ）と、はじめての準備ガイドを 1 つの画面で切りかえて表示する。
 * 状態はサービスが SharedPreferences に書き、この画面はそれを見て表示を更新する。
 */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    static final String EXTRA_MUTE_NOW = "mute_now";
    private static final int REQUEST_NOTIFICATIONS = 1;

    /** 準備のステップ。上から順に満たす */
    private enum Step { DEVELOPER, WIFI, PAIR }

    private View mHome;
    private View mGuide;

    private TextView mHeroTitle;
    private TextView mHeroSub;
    private View mErrorCard;
    private TextView mErrorText;
    private LinearLayout mToggleCard;
    private View mToggleGlyph;
    private ImageView mToggleIcon;
    private ProgressBar mToggleProgress;
    private TextView mToggleTitle;
    private TextView mToggleSub;
    private PillSwitch mToggleSwitch;
    private TextView mInfoTitle;
    private TextView mInfoSub;

    private LinearLayout mSegments;
    private TextView mGuideLabel;
    private TextView mGuideTitle;
    private TextView mGuideLead;
    private View mIllusDeveloper;
    private View mIllusWifi;
    private View mIllusPair;
    private LinearLayout mGuideSteps;
    private TextView mGuideStatus;
    private TextView mGuideCta;

    private Runnable mPendingAction;
    /** ユーザーが「はじめから準備しなおす」を選んだ */
    private boolean mForceGuide;
    /** ガイドを表示中。ペアリング後の無音化が終わるまでガイドに留まる */
    private boolean mInGuide;
    private boolean mWasWorking;
    /** 直前に切りかえが完了した向き（見出しを「〜なりました」にする）。null ならふだんの表示 */
    private Boolean mJustChangedTo;
    private Step mShownStep;
    private boolean mFirstRefresh = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        applyInsets();
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横向きは高さが足りないので、見出しは文の量に合わせ、残りを操作部分に使う
            View hero = findViewById(R.id.hero);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) hero.getLayoutParams();
            lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            lp.weight = 0;
            hero.setPadding(hero.getPaddingLeft(), dp(12), hero.getPaddingRight(), dp(16));
        }
        MuteService.createChannels(this);

        ((TextView) findViewById(R.id.mini_android)).setText(Build.VERSION.RELEASE);
        ((TextView) findViewById(R.id.mini_build)).setText(Build.DISPLAY);

        mToggleCard.setOnClickListener(v -> onToggle());
        findViewById(R.id.error_copy).setOnClickListener(v -> copyLog());
        findViewById(R.id.error_close).setOnClickListener(v ->
                Device.prefs(this).edit().putString(Device.PREF_ERROR, "").apply());
        findViewById(R.id.row_log).setOnClickListener(v -> copyLog());
        findViewById(R.id.row_resetup).setOnClickListener(v -> {
            mForceGuide = true;
            refresh();
        });
        mGuideCta.setOnClickListener(v -> onGuideAction());

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_MUTE_NOW, false) && firstUnmetStep() == null) {
            withNotificationPermission(() -> startToggle(true));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Device.prefs(this).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    @Override
    protected void onPause() {
        Device.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        runOnUiThread(this::refresh);
    }

    @Override
    public void onBackPressed() {
        // 準備しなおしの途中でやめるときは、ホームに戻る
        if (mGuide.getVisibility() == View.VISIBLE && mForceGuide && firstUnmetStep() == null && !isWorking()) {
            mForceGuide = false;
            mInGuide = false;
            refresh();
            return;
        }
        super.onBackPressed();
    }

    // ---- 表示 ----

    private void refresh() {
        SharedPreferences prefs = Device.prefs(this);
        boolean working = isWorking();
        boolean pairing = prefs.getBoolean(Device.PREF_PAIRING, false) && MuteService.isAlive();
        Step unmet = firstUnmetStep();

        if (unmet != null || mForceGuide) mInGuide = true;
        if (mInGuide && unmet == null && !mForceGuide && !working && !pairing) {
            // ペアリング後の無音化まで終わった
            mInGuide = false;
            if (Device.isMuted(this)) mJustChangedTo = true;
        }
        if (mWasWorking && !working && prefs.getString(Device.PREF_ERROR, "").isEmpty()) {
            mJustChangedTo = Device.isMuted(this);
        }
        mWasWorking = working;

        if (mInGuide) {
            showGuide(unmet != null ? unmet : Step.PAIR, working, pairing);
        } else {
            showHome(working);
        }
        mFirstRefresh = false;
    }

    private void showHome(boolean working) {
        mHome.setVisibility(View.VISIBLE);
        mGuide.setVisibility(View.GONE);
        boolean muted = Device.isMuted(this);

        if (working) {
            setHero(getString(muted ? R.string.hero_muted : R.string.hero_on), getString(R.string.hero_sub_working));
        } else if (muted) {
            setHero(getString(Boolean.TRUE.equals(mJustChangedTo) ? R.string.hero_just_muted : R.string.hero_muted),
                    getString(R.string.hero_sub_muted));
        } else {
            setHero(getString(Boolean.FALSE.equals(mJustChangedTo) ? R.string.hero_just_restored : R.string.hero_on),
                    getString(R.string.hero_sub_on));
        }

        // 処理中は、切りかえ先の状態をスイッチで先に見せる
        boolean on = working ? mToggleSwitch.isChecked() : muted;
        mToggleSwitch.setChecked(on, !mFirstRefresh);
        mToggleCard.setBackgroundResource(on ? R.drawable.bg_card_on : R.drawable.bg_card);
        mToggleCard.setClipToOutline(true);
        mToggleGlyph.setBackgroundResource(on ? R.drawable.bg_glyph_on : R.drawable.bg_glyph);
        int iconColor = getColor(on ? R.color.on_blue : R.color.blue);
        mToggleIcon.setImageTintList(ColorStateList.valueOf(iconColor));
        mToggleProgress.setIndeterminateTintList(ColorStateList.valueOf(iconColor));
        mToggleIcon.setVisibility(working ? View.INVISIBLE : View.VISIBLE);
        mToggleProgress.setVisibility(working ? View.VISIBLE : View.GONE);
        mToggleTitle.setTextColor(getColor(on ? R.color.on_blue : R.color.ink));
        mToggleSub.setTextColor(getColor(on ? R.color.on_blue_sub : R.color.sub));
        mToggleSub.setText(working ? R.string.toggle_working : R.string.toggle_sub);
        mToggleCard.setEnabled(!working);
        mToggleCard.setContentDescription(getString(R.string.toggle_description,
                getString(on ? R.string.state_on : R.string.state_off)));

        if (Boolean.TRUE.equals(mJustChangedTo) && muted) {
            mInfoTitle.setText(R.string.info_try_title);
            mInfoSub.setText(R.string.info_try_sub);
        } else {
            mInfoTitle.setText(R.string.info_ready_title);
            mInfoSub.setText(R.string.info_ready_sub);
        }

        String error = Device.prefs(this).getString(Device.PREF_ERROR, "");
        mErrorCard.setVisibility(error.isEmpty() || working ? View.GONE : View.VISIBLE);
        mErrorText.setText(error);
    }

    private void setHero(String title, String sub) {
        if (!title.contentEquals(mHeroTitle.getText()) && !mFirstRefresh) {
            mHeroTitle.setAlpha(0f);
            mHeroTitle.animate().alpha(1f).setDuration(250).start();
        }
        mHeroTitle.setText(title);
        mHeroSub.setText(sub);
    }

    private void showGuide(Step step, boolean working, boolean pairing) {
        mHome.setVisibility(View.GONE);
        mGuide.setVisibility(View.VISIBLE);
        Step[] all = Step.values();
        if (step != mShownStep) {
            buildSegments(step.ordinal());
            mShownStep = step;
        }
        mGuideLabel.setText(getString(R.string.guide_label, step.ordinal() + 1, all.length));
        mIllusDeveloper.setVisibility(step == Step.DEVELOPER ? View.VISIBLE : View.GONE);
        mIllusWifi.setVisibility(step == Step.WIFI ? View.VISIBLE : View.GONE);
        mIllusPair.setVisibility(step == Step.PAIR ? View.VISIBLE : View.GONE);

        String status = null;
        switch (step) {
            case DEVELOPER:
                mGuideTitle.setText(R.string.dev_title);
                setLead(0);
                setSteps(R.string.dev_step1, R.string.dev_step2, R.string.dev_step3, R.string.dev_step4);
                mGuideCta.setText(R.string.dev_cta);
                break;
            case WIFI:
                mGuideTitle.setText(R.string.wifi_title);
                setLead(R.string.wifi_lead);
                setSteps(R.string.wifi_step1, R.string.wifi_step2, R.string.wifi_step3);
                mGuideCta.setText(R.string.wifi_cta);
                break;
            case PAIR:
                mGuideTitle.setText(R.string.pair_title);
                setLead(0);
                setSteps(R.string.pair_step1, R.string.pair_step2, R.string.pair_step3, R.string.pair_step4);
                String error = Device.prefs(this).getString(Device.PREF_ERROR, "");
                if (working) {
                    status = getString(R.string.pair_status_working);
                } else if (!error.isEmpty()) {
                    status = error;
                } else if (pairing && Device.prefs(this).getBoolean(Device.PREF_PAIRING_READY, false)) {
                    status = getString(R.string.pair_status_ready);
                } else if (pairing) {
                    status = getString(R.string.pair_status_waiting);
                }
                mGuideCta.setText(pairing || working ? R.string.pair_cta_again : R.string.pair_cta);
                break;
        }
        mGuideStatus.setVisibility(status == null ? View.GONE : View.VISIBLE);
        mGuideStatus.setText(status);
        boolean error = !Device.prefs(this).getString(Device.PREF_ERROR, "").isEmpty() && !working;
        mGuideStatus.setTextColor(getColor(error ? R.color.error : R.color.blue));
    }

    private void buildSegments(int current) {
        mSegments.removeAllViews();
        int count = Step.values().length;
        for (int i = 0; i < count; i++) {
            View seg = new View(this);
            seg.setBackgroundResource(i <= current ? R.drawable.bg_seg_on : R.drawable.bg_seg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            mSegments.addView(seg, lp);
        }
    }

    /** @param lead 説明文の文字列リソース。0 なら説明文を出さない */
    private void setLead(int lead) {
        mGuideLead.setVisibility(lead == 0 ? View.GONE : View.VISIBLE);
        if (lead != 0) mGuideLead.setText(lead);
    }

    private void setSteps(int... steps) {
        mGuideSteps.removeAllViews();
        for (int i = 0; i < steps.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 番号と本文のベースラインをそろえると、2 行目が行の外にはみ出して切れる
            row.setBaselineAligned(false);
            row.setPadding(0, i == 0 ? 0 : dp(12), 0, 0);

            TextView num = new TextView(this);
            num.setText(String.valueOf(i + 1));
            num.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            num.setTextColor(getColor(R.color.ink));
            num.setGravity(android.view.Gravity.CENTER);
            num.setBackgroundResource(R.drawable.bg_num);
            num.setIncludeFontPadding(false);
            row.addView(num, new LinearLayout.LayoutParams(dp(24), dp(24)));

            TextView text = new TextView(this);
            text.setText(steps[i]);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            text.setTextColor(getColor(R.color.ink));
            text.setLineSpacing(dp(5), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(12));
            lp.topMargin = dp(1);
            row.addView(text, lp);
            mGuideSteps.addView(row);
        }
    }

    // ---- 操作 ----

    private void onToggle() {
        if (isWorking()) return;
        if (firstUnmetStep() != null) {
            refresh();
            return;
        }
        boolean mute = !Device.isMuted(this);
        withNotificationPermission(() -> startToggle(mute));
    }

    private void startToggle(boolean mute) {
        mJustChangedTo = null;
        mToggleSwitch.setChecked(mute, true);
        Device.prefs(this).edit().putString(Device.PREF_ERROR, "").apply();
        MuteService.start(this, mute ? MuteService.ACTION_MUTE : MuteService.ACTION_RESTORE);
    }

    private void onGuideAction() {
        Step step = mShownStep;
        if (step == null) return;
        switch (step) {
            case DEVELOPER:
                Device.openDeviceInfoSettings(this);
                break;
            case WIFI:
                Device.openWifiSettings(this);
                break;
            case PAIR:
                withNotificationPermission(() -> {
                    mForceGuide = false;
                    Device.prefs(this).edit().putString(Device.PREF_ERROR, "").apply();
                    if (!isWorking()) MuteService.startPairing(this, true);
                    Device.openWirelessDebuggingSettings(this);
                });
                break;
        }
    }

    /** まだ済んでいない最初の準備ステップ。すべて済んでいれば null */
    private Step firstUnmetStep() {
        if (!Device.isDeveloperOptionsEnabled(this)) return Step.DEVELOPER;
        if (!Device.isWifiConnected(this)) return Step.WIFI;
        boolean paired = Device.prefs(this).getBoolean(Device.PREF_PAIRED, false);
        boolean canConnect = Device.canToggleWirelessDebugging(this) || Device.isWirelessDebuggingOn(this);
        if (!paired || !canConnect) return Step.PAIR;
        return null;
    }

    private boolean isWorking() {
        return Device.prefs(this).getBoolean(Device.PREF_WORKING, false) && MuteService.isAlive();
    }

    private void copyLog() {
        String log = "Hushutter " + BuildConfig.VERSION_NAME + " / " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + "\n" + Device.readLog(this);
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("log", log));
        Toast.makeText(this, R.string.toast_log_copied, Toast.LENGTH_SHORT).show();
    }

    private void withNotificationPermission(Runnable action) {
        // 通知の許可を求める仕組みは Android 13 から。それより前は確認せずに進める
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            action.run();
            return;
        }
        mPendingAction = action;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATIONS) return;
        Runnable action = mPendingAction;
        mPendingAction = null;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (action != null) action.run();
        } else {
            Device.prefs(this).edit().putString(Device.PREF_ERROR,
                    getString(R.string.error_notification_permission, getString(R.string.app_name))).apply();
        }
    }

    // ---- 準備 ----

    private void bindViews() {
        mHome = findViewById(R.id.home);
        mGuide = findViewById(R.id.guide);
        mHeroTitle = findViewById(R.id.hero_title);
        mHeroSub = findViewById(R.id.hero_sub);
        mErrorCard = findViewById(R.id.error_card);
        mErrorText = findViewById(R.id.error_text);
        mToggleCard = findViewById(R.id.toggle_card);
        mToggleGlyph = findViewById(R.id.toggle_glyph);
        mToggleIcon = findViewById(R.id.toggle_icon);
        mToggleProgress = findViewById(R.id.toggle_progress);
        mToggleTitle = findViewById(R.id.toggle_title);
        mToggleSub = findViewById(R.id.toggle_sub);
        mToggleSwitch = findViewById(R.id.toggle_switch);
        mInfoTitle = findViewById(R.id.info_title);
        mInfoSub = findViewById(R.id.info_sub);
        mSegments = findViewById(R.id.guide_segments);
        mGuideLabel = findViewById(R.id.guide_label);
        mGuideTitle = findViewById(R.id.guide_title);
        mGuideLead = findViewById(R.id.guide_lead);
        mIllusDeveloper = findViewById(R.id.illus_developer);
        mIllusWifi = findViewById(R.id.illus_wifi);
        mIllusPair = findViewById(R.id.illus_pair);
        mGuideSteps = findViewById(R.id.guide_steps);
        mGuideStatus = findViewById(R.id.guide_status);
        mGuideCta = findViewById(R.id.guide_cta);
    }

    /** 画面の端まで描く（Android 15 以降は必須）ので、ステータスバーとナビゲーションバーの分だけ内側に寄せる */
    private void applyInsets() {
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}
