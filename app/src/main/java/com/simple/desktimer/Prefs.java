package com.simple.desktimer;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有设置项，保存在 SharedPreferences 中，下次打开自动恢复。 */
public class Prefs {

    public static final int MODE_UP = 0;        // 正常计时
    public static final int MODE_DOWN = 1;      // 倒计时
    public static final int MODE_STAGES = 2;    // 多段计时

    public static final int ORIENT_LANDSCAPE = 0;
    public static final int ORIENT_PORTRAIT = 1;
    public static final int ORIENT_AUTO = 2;

    /** 倒计时预设：自定义 */
    public static final int PRESET_CUSTOM = -1;

    public static final int STAGE_COUNT = 3;

    private final SharedPreferences sp;

    public int mode;
    // ---- 倒计时 ----
    public int countdownPreset;      // 180 / 300 / 600 秒，或 PRESET_CUSTOM
    public int customSeconds;        // 自定义倒计时（秒）
    public int remindSec;            // 结束前多少秒响提示音，0 = 不提醒
    // ---- 多段计时 ----
    public final boolean[] stageOn = new boolean[STAGE_COUNT];
    public final String[] stageName = new String[STAGE_COUNT];
    public final int[] stageSec = new int[STAGE_COUNT];
    public final int[] stageRemind = new int[STAGE_COUNT];
    public boolean autoNext;         // 上一段结束自动进入下一段
    public int stageBellSec;         // 段间铃声时长
    // ---- 倒计时/多段共用 ----
    public boolean showOvertime;
    public boolean warnLastMinute;
    // ---- 铃声 ----
    public boolean ringEnabled;
    public String ringUri;           // 空字符串 = 系统默认闹钟铃声
    public String ringName;
    public int volume;               // 0..100，相对系统闹钟音量
    public int ringSeconds;          // 最终响铃时长（秒），0 = 直到手动停止
    public boolean vibrate;
    // ---- 显示 ----
    public boolean tapToPause;
    public boolean use24;
    public boolean keepScreenOn;
    public boolean fullscreen;
    public int orientation;
    public String motto;

    private static final String[] DEF_NAME = {"答辩", "提问", "点评"};
    private static final int[] DEF_SEC = {600, 300, 300};
    private static final int[] DEF_REMIND = {60, 0, 0};
    private static final boolean[] DEF_ON = {true, true, false};

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        load();
    }

    public final void load() {
        mode = sp.getInt("mode", MODE_DOWN);
        countdownPreset = sp.getInt("countdownPreset", 300);
        customSeconds = sp.getInt("customSeconds", 15 * 60);
        remindSec = sp.getInt("remindSec", 0);

        for (int i = 0; i < STAGE_COUNT; i++) {
            stageOn[i] = i == 0 || sp.getBoolean("stageOn" + i, DEF_ON[i]);
            stageName[i] = sp.getString("stageName" + i, DEF_NAME[i]);
            stageSec[i] = sp.getInt("stageSec" + i, DEF_SEC[i]);
            stageRemind[i] = sp.getInt("stageRemind" + i, DEF_REMIND[i]);
        }
        autoNext = sp.getBoolean("autoNext", true);
        stageBellSec = sp.getInt("stageBellSec", 5);

        showOvertime = sp.getBoolean("showOvertime", true);
        warnLastMinute = sp.getBoolean("warnLastMinute", true);

        ringEnabled = sp.getBoolean("ringEnabled", true);
        ringUri = sp.getString("ringUri", "");
        ringName = sp.getString("ringName", "");
        volume = sp.getInt("volume", 80);
        ringSeconds = sp.getInt("ringSeconds", 30);
        vibrate = sp.getBoolean("vibrate", true);

        tapToPause = sp.getBoolean("tapToPause", true);
        use24 = sp.getBoolean("use24", true);
        keepScreenOn = sp.getBoolean("keepScreenOn", true);
        fullscreen = sp.getBoolean("fullscreen", true);
        orientation = sp.getInt("orientation", ORIENT_LANDSCAPE);
        motto = sp.getString("motto", "");
    }

    public void save() {
        SharedPreferences.Editor e = sp.edit()
                .putInt("mode", mode)
                .putInt("countdownPreset", countdownPreset)
                .putInt("customSeconds", customSeconds)
                .putInt("remindSec", remindSec)
                .putBoolean("autoNext", autoNext)
                .putInt("stageBellSec", stageBellSec)
                .putBoolean("showOvertime", showOvertime)
                .putBoolean("warnLastMinute", warnLastMinute)
                .putBoolean("ringEnabled", ringEnabled)
                .putString("ringUri", ringUri)
                .putString("ringName", ringName)
                .putInt("volume", volume)
                .putInt("ringSeconds", ringSeconds)
                .putBoolean("vibrate", vibrate)
                .putBoolean("tapToPause", tapToPause)
                .putBoolean("use24", use24)
                .putBoolean("keepScreenOn", keepScreenOn)
                .putBoolean("fullscreen", fullscreen)
                .putInt("orientation", orientation)
                .putString("motto", motto);
        for (int i = 0; i < STAGE_COUNT; i++) {
            e.putBoolean("stageOn" + i, stageOn[i])
                    .putString("stageName" + i, stageName[i])
                    .putInt("stageSec" + i, stageSec[i])
                    .putInt("stageRemind" + i, stageRemind[i]);
        }
        e.apply();
    }

    /** 恢复“答辩模板”：答辩 10 分钟（剩 1 分钟提示）+ 提问 5 分钟 */
    public static String defaultStageName(int i) { return DEF_NAME[i]; }
    public static int defaultStageSec(int i) { return DEF_SEC[i]; }
    public static int defaultStageRemind(int i) { return DEF_REMIND[i]; }
    public static boolean defaultStageOn(int i) { return DEF_ON[i]; }

    /** 当前设置下的倒计时总秒数 */
    public int countdownSeconds() {
        return countdownPreset == PRESET_CUSTOM ? customSeconds : countdownPreset;
    }

    /** 滑块百分比 → 播放增益（平方曲线，听感更线性） */
    public static float volumeToGain(int percent) {
        float f = Math.max(0, Math.min(100, percent)) / 100f;
        return f * f;
    }
}
