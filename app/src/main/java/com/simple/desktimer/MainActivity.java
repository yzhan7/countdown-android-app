package com.simple.desktimer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.NumberPicker;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_SYSTEM_RINGTONE = 101;
    private static final int REQ_AUDIO_FILE = 102;

    private static final int COLOR_NORMAL = 0xFFFFFFFF;
    private static final int COLOR_PAUSED = 0x88FFFFFF;
    private static final int COLOR_WARN = 0xFFFFB74D;
    private static final int COLOR_OVER = 0xFFFF6B6B;

    /** 结束铃声时长选项（秒），0 = 直到手动停止 */
    private static final int[] RING_SECONDS = {10, 30, 60, 0};
    private static final String[] RING_LABELS = {"10 秒", "30 秒", "1 分钟", "直到手动停止"};
    /** 结束前提示音选项（秒），0 = 不提醒 */
    private static final int[] REMIND_SECONDS = {0, 30, 60, 120, 300};
    private static final String[] REMIND_LABELS = {"不提醒", "剩 30 秒", "剩 1 分钟", "剩 2 分钟", "剩 5 分钟"};
    /** 段间铃声时长（秒） */
    private static final int[] BELL_SECONDS = {3, 5, 10};
    private static final String[] BELL_LABELS = {"3 秒", "5 秒", "10 秒"};

    /** 一个计时段。普通倒计时 = 只有一段的多段计时。 */
    private static class Stage {
        final String name;
        final long ms;
        final long remindMs;

        Stage(String name, long ms, long remindMs) {
            this.name = name;
            this.ms = ms;
            this.remindMs = remindMs;
        }
    }

    private Prefs prefs;
    private View root;
    private TextView tvMotto, tvSmallClock, tvBig, tvSub;
    private View btnSettings, btnStart, btnRestart, btnBack;
    private Button btnStopRing, btnNext;
    private int basePadH, basePadV;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AlarmPlayer alarmPlayer = new AlarmPlayer();
    private final AlarmPlayer previewPlayer = new AlarmPlayer();
    private Vibrator vibrator;

    // ---- 计时状态（基于开机时长，不受修改系统时间影响）----
    private boolean timing, paused, finished;
    private int mode;
    private long startAt, pausedTotal, pauseStartAt;
    private final List<Stage> stages = new ArrayList<>();
    private int stageIdx;
    private long stageStart;              // 当前段开始时的“有效计时”毫秒数
    private boolean reminded, stageEnded, waitingNext;
    private boolean ringing, ringingFinal;

    // ---- 设置窗口的临时状态 ----
    private AlertDialog settingsDialog;
    private TextView dlgRingName;
    private Button dlgPreviewBtn;
    private String tmpRingUri = "", tmpRingName = "";

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
    private final SimpleDateFormat weekFmt = new SimpleDateFormat("EEEE", Locale.CHINA);
    private SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, timing ? 100 : 250);
        }
    };
    private final Runnable autoStopRing = this::stopRinging;
    private final Runnable autoStopPreview = this::stopPreview;

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new Prefs(this);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        root = findViewById(R.id.root);
        tvMotto = findViewById(R.id.tvMotto);
        tvSmallClock = findViewById(R.id.tvSmallClock);
        tvBig = findViewById(R.id.tvBig);
        tvSub = findViewById(R.id.tvSub);
        btnSettings = findViewById(R.id.btnSettings);
        btnStart = findViewById(R.id.btnStart);
        btnRestart = findViewById(R.id.btnRestart);
        btnBack = findViewById(R.id.btnBack);
        btnStopRing = findViewById(R.id.btnStopRing);
        btnNext = findViewById(R.id.btnNext);

        basePadH = root.getPaddingLeft();
        basePadV = root.getPaddingTop();
        root.setOnApplyWindowInsetsListener(this::onInsets);

        btnSettings.setOnClickListener(v -> showSettingsDialog());
        btnStart.setOnClickListener(v -> startTiming());
        btnRestart.setOnClickListener(v -> startTiming());
        btnBack.setOnClickListener(v -> backToClock());
        btnStopRing.setOnClickListener(v -> stopRinging());
        btnNext.setOnClickListener(v -> goNextStage());
        tvBig.setOnClickListener(v -> onBigTextTapped());
        // “下一段”按钮出现时，给底部文字留出左右空间，保证文字居中且不被遮挡
        btnNext.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> tvSub.post(this::updateSubPadding));

        applyDisplaySettings();
        applyModeUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        applyFullscreen();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 不在计时时，退到后台就停止刷新以省电；计时中保持刷新，以便按时响铃
        if (!timing) handler.removeCallbacks(ticker);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyFullscreen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        alarmPlayer.stop();
        previewPlayer.stop();
        if (vibrator != null) vibrator.cancel();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (timing) {
            backToClock();      // 计时界面按返回键 → 回到主界面
        } else {
            super.onBackPressed();
        }
    }

    // =====================================================================
    // 计时逻辑
    // =====================================================================

    private void startTiming() {
        stopRinging();
        mode = prefs.mode;
        stages.clear();
        if (mode == Prefs.MODE_DOWN) {
            long ms = prefs.countdownSeconds() * 1000L;
            if (ms <= 0) {
                Toast.makeText(this, "请先在设置中设定倒计时时间", Toast.LENGTH_SHORT).show();
                return;
            }
            stages.add(new Stage("倒计时", ms, prefs.remindSec * 1000L));
        } else if (mode == Prefs.MODE_STAGES) {
            for (int i = 0; i < Prefs.STAGE_COUNT; i++) {
                if (prefs.stageOn[i] && prefs.stageSec[i] > 0) {
                    stages.add(new Stage(prefs.stageName[i], prefs.stageSec[i] * 1000L,
                            prefs.stageRemind[i] * 1000L));
                }
            }
            if (stages.isEmpty()) {
                Toast.makeText(this, "请先在设置中设定各段时间", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        timing = true;
        paused = false;
        finished = false;
        startAt = SystemClock.elapsedRealtime();
        pausedTotal = 0;
        stageIdx = 0;
        stageStart = 0;
        reminded = false;
        stageEnded = false;
        waitingNext = false;
        applyModeUi();
        updateUi();
    }

    private void backToClock() {
        stopRinging();
        timing = false;
        paused = false;
        finished = false;
        waitingNext = false;
        applyModeUi();
        updateUi();
    }

    /** 手动切换到下一段（提前结束本段，或“不自动进入下一段”时使用） */
    private void goNextStage() {
        if (!timing || mode != Prefs.MODE_STAGES || stageIdx >= stages.size() - 1) return;
        stopRinging();
        if (paused) {
            pausedTotal += SystemClock.elapsedRealtime() - pauseStartAt;
            paused = false;
        }
        stageIdx++;
        stageStart = elapsedMs();
        reminded = false;
        stageEnded = false;
        waitingNext = false;
        updateUi();
    }

    private void onBigTextTapped() {
        if (ringing) {           // 响铃时轻触数字 = 停止响铃
            stopRinging();
            return;
        }
        if (!timing || finished || !prefs.tapToPause) return;
        long now = SystemClock.elapsedRealtime();
        if (paused) {
            pausedTotal += now - pauseStartAt;
            paused = false;
        } else {
            pauseStartAt = now;
            paused = true;
        }
        updateUi();
    }

    /** 有效计时时长（扣除暂停时间） */
    private long elapsedMs() {
        long now = paused ? pauseStartAt : SystemClock.elapsedRealtime();
        return now - startAt - pausedTotal;
    }

    /** 检查提示音、段结束、自动进入下一段 */
    private void advanceStages(long elapsed) {
        while (true) {
            Stage st = stages.get(stageIdx);
            long remain = st.ms - (elapsed - stageStart);

            if (!reminded && st.remindMs > 0 && st.remindMs < st.ms && remain <= st.remindMs) {
                reminded = true;
                if (remain > 0) playReminder(prefs.volume);   // 已错过结束点（如后台恢复）就不补播
            }
            if (remain > 0 || stageEnded) return;

            stageEnded = true;
            boolean last = stageIdx == stages.size() - 1;
            if (last) {
                finished = true;
                startRinging(true);
                return;
            }
            if (prefs.autoNext) {
                stageStart += st.ms;      // 严格按计划时间衔接，总时长不漂移
                stageIdx++;
                reminded = false;
                stageEnded = false;
                startRinging(false);
                // 继续循环：万一后台冻结期间跨过了多段，这里会一次补齐
            } else {
                waitingNext = true;
                startRinging(false);
                return;
            }
        }
    }

    private void updateUi() {
        long nowWall = System.currentTimeMillis();
        String clock = clockFmt.format(new Date(nowWall));

        if (!timing) {
            setTextIfChanged(tvBig, clock);
            setColorIfChanged(tvBig, COLOR_NORMAL);
            setTextIfChanged(tvSub, buildDateLine(nowWall));
            return;
        }

        setTextIfChanged(tvSmallClock, clock);
        long elapsed = elapsedMs();
        String big, sub;
        int color;
        String hint = prefs.tapToPause ? " · 轻触数字暂停" : "";

        if (mode == Prefs.MODE_UP) {
            big = formatSecs(elapsed / 1000);
            color = paused ? COLOR_PAUSED : COLOR_NORMAL;
            sub = paused ? "已暂停 · 轻触数字继续" : "正计时" + hint;
        } else {
            advanceStages(elapsed);
            Stage st = stages.get(stageIdx);
            long remain = st.ms - (elapsed - stageStart);
            boolean last = stageIdx == stages.size() - 1;
            String head = mode == Prefs.MODE_STAGES
                    ? st.name + "（第 " + (stageIdx + 1) + "/" + stages.size() + " 段）"
                    : "倒计时";

            if (remain > 0) {
                big = formatSecs((remain + 999) / 1000);   // 向上取整：恰好在 00:00 时结束
                color = paused ? COLOR_PAUSED
                        : (prefs.warnLastMinute && remain <= 60_000 ? COLOR_WARN : COLOR_NORMAL);
                sub = paused ? "已暂停 · 轻触数字继续" : head + " " + formatSecs(st.ms / 1000) + hint;
            } else {
                big = prefs.showOvertime ? "-" + formatSecs(-remain / 1000) : formatSecs(0);
                color = COLOR_OVER;
                String over = prefs.showOvertime ? " · 已超时" : "";
                if (last) {
                    sub = (mode == Prefs.MODE_STAGES ? "全部结束" : "时间到") + over;
                } else {
                    sub = st.name + " 时间到" + over;
                }
            }
        }

        setTextIfChanged(tvBig, big);
        setColorIfChanged(tvBig, color);
        setTextIfChanged(tvSub, sub);
        updateNextButton();
        // 最终响铃时数字闪烁
        tvBig.setAlpha(ringingFinal && (SystemClock.elapsedRealtime() / 500) % 2 == 1 ? 0.35f : 1f);
    }

    private void updateNextButton() {
        boolean show = timing && mode == Prefs.MODE_STAGES && stageIdx < stages.size() - 1
                && !ringingFinal;
        if (show) {
            String text = waitingNext ? "开始：" + stages.get(stageIdx + 1).name + " ›" : "下一段 ›";
            setTextIfChanged(btnNext, text);
        }
        int vis = show ? View.VISIBLE : View.GONE;
        if (btnNext.getVisibility() != vis) {
            btnNext.setVisibility(vis);
            updateSubPadding();
        }
    }

    private void updateSubPadding() {
        int pad = btnNext.getVisibility() == View.VISIBLE ? btnNext.getWidth() + dp(8) : 0;
        if (tvSub.getPaddingLeft() != pad) tvSub.setPadding(pad, 0, pad, 0);
    }

    // =====================================================================
    // 响铃 / 提示音 / 振动
    // =====================================================================

    /** @param isFinal true=最终结束铃（时长按设置，显示“停止响铃”按钮）；false=段间铃声 */
    private void startRinging(boolean isFinal) {
        int seconds = isFinal ? prefs.ringSeconds : prefs.stageBellSec;
        boolean any = false;
        if (prefs.ringEnabled) {
            any = alarmPlayer.start(this, prefs.ringUri, Prefs.volumeToGain(prefs.volume));
        }
        if (prefs.vibrate && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 700, 500}, 0));
            any = true;
        }
        if (!any) return;
        ringing = true;
        ringingFinal = isFinal;
        if (isFinal) {
            tvSub.setVisibility(View.INVISIBLE);
            btnStopRing.setVisibility(View.VISIBLE);
        }
        handler.removeCallbacks(autoStopRing);
        if (seconds > 0) handler.postDelayed(autoStopRing, seconds * 1000L);
    }

    private void stopRinging() {
        handler.removeCallbacks(autoStopRing);
        alarmPlayer.stop();
        if (vibrator != null) vibrator.cancel();
        ringing = false;
        ringingFinal = false;
        btnStopRing.setVisibility(View.GONE);
        tvSub.setVisibility(View.VISIBLE);
        tvBig.setAlpha(1f);
    }

    /** 短促的“嘀、嘀、嘀”提示音（不打断计时）。音量 0..100 */
    private void playReminder(int volume) {
        if (volume > 0) {
            ToneGenerator tg = null;
            try {
                tg = new ToneGenerator(AudioManager.STREAM_ALARM, Math.min(100, volume));
            } catch (RuntimeException ignored) {
            }
            if (tg != null) {
                final ToneGenerator gen = tg;
                for (int i = 0; i < 3; i++) {
                    handler.postDelayed(() -> gen.startTone(ToneGenerator.TONE_PROP_BEEP, 180), i * 380L);
                }
                handler.postDelayed(gen::release, 1600);
            }
        }
        if (prefs.vibrate && vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    // =====================================================================
    // 界面切换与显示设置
    // =====================================================================

    private void applyModeUi() {
        btnSettings.setVisibility(timing ? View.GONE : View.VISIBLE);
        btnStart.setVisibility(timing ? View.GONE : View.VISIBLE);
        btnRestart.setVisibility(timing ? View.VISIBLE : View.GONE);
        btnBack.setVisibility(timing ? View.VISIBLE : View.GONE);
        tvSmallClock.setVisibility(timing ? View.VISIBLE : View.GONE);
        boolean showMotto = !timing && !prefs.motto.isEmpty();
        tvMotto.setVisibility(showMotto ? View.VISIBLE : View.GONE);
        tvMotto.setText(prefs.motto);
        tvBig.setAlpha(1f);
        updateNextButton();
    }

    private void applyDisplaySettings() {
        clockFmt = new SimpleDateFormat(prefs.use24 ? "HH:mm:ss" : "hh:mm:ss", Locale.CHINA);

        // 屏幕常亮：本应用在前台时系统不会自动息屏（无需修改手机设置）
        if (prefs.keepScreenOn) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        int o;
        switch (prefs.orientation) {
            case Prefs.ORIENT_PORTRAIT: o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT; break;
            case Prefs.ORIENT_AUTO: o = ActivityInfo.SCREEN_ORIENTATION_FULL_USER; break;
            default: o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; break;
        }
        if (getRequestedOrientation() != o) setRequestedOrientation(o);

        applyFullscreen();
    }

    @SuppressWarnings("deprecation")
    private void applyFullscreen() {
        boolean fs = prefs.fullscreen;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            int types = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
            if (fs) {
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(types);
            } else {
                c.show(types);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(fs
                    ? (View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    : View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    /** 避开状态栏 / 导航栏 / 刘海 */
    @SuppressWarnings("deprecation")
    private WindowInsets onInsets(View v, WindowInsets insets) {
        int l, t, r, b;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets in = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = in.left; t = in.top; r = in.right; b = in.bottom;
        } else {
            l = insets.getSystemWindowInsetLeft();
            t = insets.getSystemWindowInsetTop();
            r = insets.getSystemWindowInsetRight();
            b = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutout dc = insets.getDisplayCutout();
                if (dc != null) {
                    l = Math.max(l, dc.getSafeInsetLeft());
                    t = Math.max(t, dc.getSafeInsetTop());
                    r = Math.max(r, dc.getSafeInsetRight());
                    b = Math.max(b, dc.getSafeInsetBottom());
                }
            }
        }
        v.setPadding(basePadH + l, basePadV + t, basePadH + r, basePadV + b);
        return insets;
    }

    // =====================================================================
    // 设置窗口
    // =====================================================================

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;
        AlertDialog.Builder builder =
                new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        Context dctx = builder.getContext();
        View v = LayoutInflater.from(dctx).inflate(R.layout.dialog_settings, null);

        RadioGroup rgMode = v.findViewById(R.id.rgMode);
        View sectionCountdown = v.findViewById(R.id.sectionCountdown);
        View sectionStages = v.findViewById(R.id.sectionStages);
        View sectionDownOpts = v.findViewById(R.id.sectionDownOpts);
        RadioGroup rgPreset = v.findViewById(R.id.rgPreset);
        View rowCustom = v.findViewById(R.id.rowCustom);
        NumberPicker npH = v.findViewById(R.id.npH);
        NumberPicker npM = v.findViewById(R.id.npM);
        NumberPicker npS = v.findViewById(R.id.npS);
        Spinner spCdRemind = v.findViewById(R.id.spCdRemind);
        Switch swAutoNext = v.findViewById(R.id.swAutoNext);
        Spinner spStageBell = v.findViewById(R.id.spStageBell);
        Button btnTemplate = v.findViewById(R.id.btnTemplate);
        Switch swOvertime = v.findViewById(R.id.swOvertime);
        Switch swWarn = v.findViewById(R.id.swWarn);
        Switch swRing = v.findViewById(R.id.swRing);
        View sectionRing = v.findViewById(R.id.sectionRing);
        TextView tvRingName = v.findViewById(R.id.tvRingName);
        Button btnSysRing = v.findViewById(R.id.btnSysRing);
        Button btnFileRing = v.findViewById(R.id.btnFileRing);
        Button btnPreview = v.findViewById(R.id.btnPreview);
        Button btnPreviewBeep = v.findViewById(R.id.btnPreviewBeep);
        TextView tvVolume = v.findViewById(R.id.tvVolume);
        SeekBar sbVolume = v.findViewById(R.id.sbVolume);
        Spinner spDuration = v.findViewById(R.id.spDuration);
        Switch swVibrate = v.findViewById(R.id.swVibrate);
        Switch swTapPause = v.findViewById(R.id.swTapPause);
        Switch sw24 = v.findViewById(R.id.sw24);
        Switch swKeepScreen = v.findViewById(R.id.swKeepScreen);
        Switch swFullscreen = v.findViewById(R.id.swFullscreen);
        RadioGroup rgOrient = v.findViewById(R.id.rgOrient);
        EditText etMotto = v.findViewById(R.id.etMotto);

        // 三个“段”的控件
        int n = Prefs.STAGE_COUNT;
        View[] stageViews = {v.findViewById(R.id.stage1), v.findViewById(R.id.stage2), v.findViewById(R.id.stage3)};
        CheckBox[] cbOn = new CheckBox[n];
        EditText[] etName = new EditText[n], etMin = new EditText[n], etSec = new EditText[n];
        Spinner[] spRemind = new Spinner[n];
        View[] rowDetail = new View[n];
        for (int i = 0; i < n; i++) {
            View sv = stageViews[i];
            cbOn[i] = sv.findViewById(R.id.cbOn);
            etName[i] = sv.findViewById(R.id.etName);
            etMin[i] = sv.findViewById(R.id.etMin);
            etSec[i] = sv.findViewById(R.id.etSec);
            spRemind[i] = sv.findViewById(R.id.spRemind);
            rowDetail[i] = sv.findViewById(R.id.rowDetail);
            cbOn[i].setText("第 " + (i + 1) + " 段");
            setupSpinner(dctx, spRemind[i], REMIND_LABELS, REMIND_SECONDS, prefs.stageRemind[i], 0);
            fillStage(i, prefs.stageOn[i], prefs.stageName[i], prefs.stageSec[i],
                    cbOn, etName, etMin, etSec, spRemind);
            final int idx = i;
            if (i == 0) {
                cbOn[i].setChecked(true);
                cbOn[i].setEnabled(false);     // 第 1 段始终启用
            }
            cbOn[i].setOnCheckedChangeListener((b, c) -> setStageRowEnabled(idx, c, etName, rowDetail));
            setStageRowEnabled(i, cbOn[i].isChecked(), etName, rowDetail);
        }

        // ---- 初始值 ----
        rgMode.check(prefs.mode == Prefs.MODE_UP ? R.id.rbUp
                : prefs.mode == Prefs.MODE_STAGES ? R.id.rbStages : R.id.rbDown);
        int preset = prefs.countdownPreset;
        rgPreset.check(preset == 180 ? R.id.rb3 : preset == 300 ? R.id.rb5
                : preset == 600 ? R.id.rb10 : R.id.rbCustom);

        npH.setMinValue(0); npH.setMaxValue(23);
        npM.setMinValue(0); npM.setMaxValue(59);
        npS.setMinValue(0); npS.setMaxValue(59);
        npH.setValue(prefs.customSeconds / 3600);
        npM.setValue(prefs.customSeconds % 3600 / 60);
        npS.setValue(prefs.customSeconds % 60);
        setupSpinner(dctx, spCdRemind, REMIND_LABELS, REMIND_SECONDS, prefs.remindSec, 0);

        swAutoNext.setChecked(prefs.autoNext);
        setupSpinner(dctx, spStageBell, BELL_LABELS, BELL_SECONDS, prefs.stageBellSec, 1);
        btnTemplate.setOnClickListener(x -> {
            for (int i = 0; i < n; i++) {
                fillStage(i, Prefs.defaultStageOn(i), Prefs.defaultStageName(i),
                        Prefs.defaultStageSec(i), cbOn, etName, etMin, etSec, spRemind);
                spRemind[i].setSelection(indexOf(REMIND_SECONDS, Prefs.defaultStageRemind(i), 0));
            }
            swAutoNext.setChecked(true);
            Toast.makeText(this, "已恢复：答辩 10 分钟（剩 1 分钟提示）+ 提问 5 分钟",
                    Toast.LENGTH_SHORT).show();
        });

        swOvertime.setChecked(prefs.showOvertime);
        swWarn.setChecked(prefs.warnLastMinute);
        swRing.setChecked(prefs.ringEnabled);
        swVibrate.setChecked(prefs.vibrate);
        swTapPause.setChecked(prefs.tapToPause);
        sw24.setChecked(prefs.use24);
        swKeepScreen.setChecked(prefs.keepScreenOn);
        swFullscreen.setChecked(prefs.fullscreen);
        rgOrient.check(prefs.orientation == Prefs.ORIENT_PORTRAIT ? R.id.rbPort
                : prefs.orientation == Prefs.ORIENT_AUTO ? R.id.rbAuto : R.id.rbLand);
        etMotto.setText(prefs.motto);

        tmpRingUri = prefs.ringUri;
        tmpRingName = prefs.ringName;
        dlgRingName = tvRingName;
        dlgPreviewBtn = btnPreview;
        updateRingLabel();
        setupSpinner(dctx, spDuration, RING_LABELS, RING_SECONDS, prefs.ringSeconds, 1);

        sbVolume.setProgress(prefs.volume);
        tvVolume.setText("音量：" + prefs.volume + "%");
        sbVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                tvVolume.setText("音量：" + p + "%");
                previewPlayer.setGain(Prefs.volumeToGain(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });

        // ---- 联动：根据计时方式显示对应设置 ----
        Runnable refresh = () -> {
            int m = rgMode.getCheckedRadioButtonId();
            sectionCountdown.setVisibility(m == R.id.rbDown ? View.VISIBLE : View.GONE);
            sectionStages.setVisibility(m == R.id.rbStages ? View.VISIBLE : View.GONE);
            sectionDownOpts.setVisibility(m == R.id.rbUp ? View.GONE : View.VISIBLE);
            rowCustom.setVisibility(rgPreset.getCheckedRadioButtonId() == R.id.rbCustom
                    ? View.VISIBLE : View.GONE);
            setEnabledDeep(sectionRing, swRing.isChecked());
            sectionRing.setAlpha(swRing.isChecked() ? 1f : 0.4f);
        };
        rgMode.setOnCheckedChangeListener((g, id) -> refresh.run());
        rgPreset.setOnCheckedChangeListener((g, id) -> refresh.run());
        swRing.setOnCheckedChangeListener((b, c) -> refresh.run());
        refresh.run();

        btnSysRing.setOnClickListener(x -> pickSystemRingtone());
        btnFileRing.setOnClickListener(x -> pickAudioFile());
        btnPreview.setOnClickListener(x -> {
            if (previewPlayer.isPlaying()) {
                stopPreview();
            } else if (previewPlayer.start(this, tmpRingUri,
                    Prefs.volumeToGain(sbVolume.getProgress()))) {
                btnPreview.setText("停止试听");
                handler.removeCallbacks(autoStopPreview);
                handler.postDelayed(autoStopPreview, 6000);
            } else {
                Toast.makeText(this, "无法播放该铃声", Toast.LENGTH_SHORT).show();
            }
        });
        btnPreviewBeep.setOnClickListener(x -> {
            stopPreview();
            playReminder(sbVolume.getProgress());
        });

        builder.setTitle("设置")
                .setView(v)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    npH.clearFocus(); npM.clearFocus(); npS.clearFocus();

                    int m = rgMode.getCheckedRadioButtonId();
                    prefs.mode = m == R.id.rbUp ? Prefs.MODE_UP
                            : m == R.id.rbStages ? Prefs.MODE_STAGES : Prefs.MODE_DOWN;

                    int p = rgPreset.getCheckedRadioButtonId();
                    prefs.countdownPreset = p == R.id.rb3 ? 180 : p == R.id.rb5 ? 300
                            : p == R.id.rb10 ? 600 : Prefs.PRESET_CUSTOM;
                    prefs.customSeconds = npH.getValue() * 3600 + npM.getValue() * 60 + npS.getValue();
                    if (prefs.countdownPreset == Prefs.PRESET_CUSTOM && prefs.customSeconds <= 0) {
                        prefs.customSeconds = 60;
                        Toast.makeText(this, "自定义时间不能为 0，已设为 1 分钟", Toast.LENGTH_SHORT).show();
                    }
                    prefs.remindSec = valueAt(spCdRemind, REMIND_SECONDS, 0);

                    boolean anyStage = false;
                    for (int i = 0; i < n; i++) {
                        prefs.stageOn[i] = i == 0 || cbOn[i].isChecked();
                        String name = etName[i].getText().toString().trim();
                        prefs.stageName[i] = name.isEmpty() ? "第" + (i + 1) + "段" : name;
                        prefs.stageSec[i] = parseInt(etMin[i]) * 60 + parseInt(etSec[i]);
                        prefs.stageRemind[i] = valueAt(spRemind[i], REMIND_SECONDS, 0);
                        if (prefs.stageOn[i] && prefs.stageSec[i] > 0) anyStage = true;
                    }
                    if (!anyStage) {
                        prefs.stageSec[0] = Prefs.defaultStageSec(0);
                        if (prefs.mode == Prefs.MODE_STAGES) {
                            Toast.makeText(this, "各段时间都为 0，第 1 段已设为 10 分钟", Toast.LENGTH_SHORT).show();
                        }
                    }
                    prefs.autoNext = swAutoNext.isChecked();
                    prefs.stageBellSec = valueAt(spStageBell, BELL_SECONDS, 5);

                    prefs.showOvertime = swOvertime.isChecked();
                    prefs.warnLastMinute = swWarn.isChecked();
                    prefs.ringEnabled = swRing.isChecked();
                    prefs.ringUri = tmpRingUri;
                    prefs.ringName = tmpRingName;
                    prefs.volume = sbVolume.getProgress();
                    prefs.ringSeconds = valueAt(spDuration, RING_SECONDS, 30);
                    prefs.vibrate = swVibrate.isChecked();
                    prefs.tapToPause = swTapPause.isChecked();
                    prefs.use24 = sw24.isChecked();
                    prefs.keepScreenOn = swKeepScreen.isChecked();
                    prefs.fullscreen = swFullscreen.isChecked();
                    int o = rgOrient.getCheckedRadioButtonId();
                    prefs.orientation = o == R.id.rbPort ? Prefs.ORIENT_PORTRAIT
                            : o == R.id.rbAuto ? Prefs.ORIENT_AUTO : Prefs.ORIENT_LANDSCAPE;
                    prefs.motto = etMotto.getText().toString().trim();
                    prefs.save();

                    applyDisplaySettings();
                    applyModeUi();
                    updateUi();
                });

        settingsDialog = builder.create();
        settingsDialog.setOnDismissListener(d -> {
            stopPreview();
            settingsDialog = null;
            dlgRingName = null;
            dlgPreviewBtn = null;
            applyFullscreen();
        });
        settingsDialog.show();

        // 横屏时让窗口宽一些
        if (settingsDialog.getWindow() != null) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int w = (int) Math.min(dm.widthPixels * 0.92f, 600 * dm.density);
            settingsDialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private static void fillStage(int i, boolean on, String name, int sec, CheckBox[] cbOn,
                                  EditText[] etName, EditText[] etMin, EditText[] etSec,
                                  Spinner[] spRemind) {
        cbOn[i].setChecked(i == 0 || on);
        etName[i].setText(name);
        etMin[i].setText(String.valueOf(sec / 60));
        etSec[i].setText(String.format(Locale.US, "%02d", sec % 60));
    }

    private static void setStageRowEnabled(int i, boolean enabled, EditText[] etName, View[] rowDetail) {
        etName[i].setEnabled(enabled);
        etName[i].setAlpha(enabled ? 1f : 0.4f);
        setEnabledDeep(rowDetail[i], enabled);
        rowDetail[i].setAlpha(enabled ? 1f : 0.4f);
    }

    private static void setupSpinner(Context ctx, Spinner sp, String[] labels, int[] values,
                                     int current, int fallbackIndex) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        sp.setSelection(indexOf(values, current, fallbackIndex));
    }

    private static int indexOf(int[] values, int v, int fallback) {
        for (int i = 0; i < values.length; i++) if (values[i] == v) return i;
        return fallback;
    }

    private static int valueAt(Spinner sp, int[] values, int fallback) {
        int i = sp.getSelectedItemPosition();
        return (i == AdapterView.INVALID_POSITION || i >= values.length) ? fallback : values[i];
    }

    private static int parseInt(EditText et) {
        try {
            return Math.max(0, Integer.parseInt(et.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void stopPreview() {
        handler.removeCallbacks(autoStopPreview);
        previewPlayer.stop();
        if (dlgPreviewBtn != null) dlgPreviewBtn.setText("试听铃声");
    }

    private void updateRingLabel() {
        if (dlgRingName == null) return;
        String name;
        if (tmpRingUri == null || tmpRingUri.isEmpty()) {
            name = "系统默认闹钟铃声";
        } else {
            name = (tmpRingName == null || tmpRingName.isEmpty()) ? "自定义铃声" : tmpRingName;
        }
        dlgRingName.setText("当前铃声：" + name);
    }

    private void pickSystemRingtone() {
        stopPreview();
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选择铃声");
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        Uri existing = (tmpRingUri == null || tmpRingUri.isEmpty())
                ? Settings.System.DEFAULT_ALARM_ALERT_URI : Uri.parse(tmpRingUri);
        i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing);
        try {
            startActivityForResult(i, REQ_SYSTEM_RINGTONE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "本机没有系统铃声选择器，请用“选本地音频”", Toast.LENGTH_LONG).show();
        }
    }

    private void pickAudioFile() {
        stopPreview();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, REQ_AUDIO_FILE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_SYSTEM_RINGTONE) {
            Uri u = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (u == null || u.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)) {
                tmpRingUri = "";
                tmpRingName = "";
            } else {
                tmpRingUri = u.toString();
                tmpRingName = ringtoneTitle(u);
            }
        } else if (requestCode == REQ_AUDIO_FILE) {
            Uri u = data.getData();
            if (u == null) return;
            try {   // 保留长期读取权限，重启手机后仍能播放
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            tmpRingUri = u.toString();
            tmpRingName = displayName(u);
        }
        updateRingLabel();
    }

    // =====================================================================
    // 小工具
    // =====================================================================

    private String ringtoneTitle(Uri u) {
        try {
            Ringtone r = RingtoneManager.getRingtone(this, u);
            if (r != null) return r.getTitle(this);
        } catch (Exception ignored) {
        }
        return "系统铃声";
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null) return s;
            }
        } catch (Exception ignored) {
        }
        return "本地音频";
    }

    private String buildDateLine(long millis) {
        Date d = new Date(millis);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        String ampm = c.get(Calendar.AM_PM) == Calendar.AM ? "上午" : "下午";
        return dateFmt.format(d) + "      " + weekFmt.format(d) + "      " + ampm;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String formatSecs(long totalSecs) {
        long h = totalSecs / 3600, m = totalSecs % 3600 / 60, s = totalSecs % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.US, "%02d:%02d", m, s);
    }

    private static void setTextIfChanged(TextView tv, String s) {
        if (!s.contentEquals(tv.getText())) tv.setText(s);
    }

    private static void setColorIfChanged(TextView tv, int color) {
        if (tv.getCurrentTextColor() != color) tv.setTextColor(color);
    }

    private static void setEnabledDeep(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledDeep(g.getChildAt(i), enabled);
        }
    }
}
