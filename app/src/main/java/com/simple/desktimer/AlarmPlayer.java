package com.simple.desktimer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** 循环播放铃声（走“闹钟”音频通道）。指定铃声不可用时自动退回系统默认铃声。 */
public class AlarmPlayer {

    private MediaPlayer mp;

    public boolean start(Context ctx, String uriString, float gain) {
        stop();
        for (Uri uri : candidates(ctx, uriString)) {
            MediaPlayer p = new MediaPlayer();
            try {
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
                p.setDataSource(ctx, uri);
                p.setLooping(true);
                p.setVolume(gain, gain);
                p.prepare();
                p.start();
                mp = p;
                return true;
            } catch (Exception e) {
                p.release();
            }
        }
        return false;
    }

    public void setGain(float gain) {
        if (mp != null) {
            try {
                mp.setVolume(gain, gain);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isPlaying() {
        try {
            return mp != null && mp.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    public void stop() {
        if (mp != null) {
            try {
                mp.stop();
            } catch (Exception ignored) {
            }
            mp.release();
            mp = null;
        }
    }

    private static List<Uri> candidates(Context ctx, String uriString) {
        List<Uri> list = new ArrayList<>();
        if (uriString != null && !uriString.isEmpty()) {
            list.add(Uri.parse(uriString));
        }
        int[] types = {RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_RINGTONE};
        for (int t : types) {
            try {
                Uri u = RingtoneManager.getActualDefaultRingtoneUri(ctx, t);
                if (u != null) list.add(u);
            } catch (Exception ignored) {
            }
            Uri d = RingtoneManager.getDefaultUri(t);
            if (d != null) list.add(d);
        }
        return list;
    }
}
