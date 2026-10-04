package jp.junya.kaisatsu;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.NfcF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** おもちゃの改札。Suica・PASMOなどをかざすと「ピッ」と鳴る。カードには書き込まない（読むだけ）。 */
public class MainActivity extends Activity implements NfcAdapter.ReaderCallback {
    static final String STATION = "南浦和";
    static final int START_BALANCE = 1000; // おもちゃの残高（最初）
    static final int FARE = 150;           // 1回の運賃
    static final int CHARGE = 1000;

    NfcAdapter nfc;
    SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    LinearLayout root;
    TextView title, main, sub, real;
    String lastCard;
    long lastTime;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("cards", MODE_PRIVATE);
        nfc = NfcAdapter.getDefaultAdapter(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(40, 60, 40, 40);

        title = text(STATION + "駅", 44, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        TextView en = text("Minami-Urawa", 20, Color.WHITE);
        main = text("", 56, Color.WHITE);
        main.setTypeface(Typeface.DEFAULT_BOLD);
        sub = text("", 30, Color.WHITE);
        real = text("", 16, Color.argb(200, 255, 255, 255));

        root.addView(title); root.addView(en);
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(-1, 0, 1f);
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setGravity(Gravity.CENTER);
        mid.addView(main); mid.addView(sub); mid.addView(real);
        root.addView(mid, grow);

        Button charge = new Button(this);
        charge.setText("チャージ ＋" + CHARGE + "円（さいごのカード）");
        charge.setTextSize(18);
        charge.setOnClickListener(v -> {
            if (lastCard == null) { idle("先にカードをタッチしてね"); return; }
            int bal = prefs.getInt(lastCard + ":bal", START_BALANCE) + CHARGE;
            prefs.edit().putInt(lastCard + ":bal", bal).apply();
            beep(new double[]{1500, 2000}, 0.08, 0.04);
            show(Color.rgb(0, 120, 200), "チャージ", "残高 " + bal + "円", "");
        });
        charge.setOnLongClickListener(v -> { prefs.edit().clear().apply(); idle("ぜんぶリセットしました"); return true; });
        root.addView(charge);
        setContentView(root);

        if (nfc == null) show(Color.DKGRAY, "NFCなし", "このスマホでは使えません", "");
        else idle(null);
    }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color); t.setGravity(Gravity.CENTER);
        return t;
    }

    @Override protected void onResume() {
        super.onResume();
        if (nfc == null) return;
        if (!nfc.isEnabled()) show(Color.DKGRAY, "NFCがオフ", "設定でNFCをオンにしてね", "");
        nfc.enableReaderMode(this, this,
                NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                        | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);
    }

    @Override protected void onPause() {
        super.onPause();
        if (nfc != null) nfc.disableReaderMode(this);
    }

    @Override public void onTagDiscovered(Tag tag) {
        String id = hex(tag.getId());
        long now = System.currentTimeMillis();
        if (id.equals(lastCard) && now - lastTime < 1500) return; // 同じカードの二度読み防止
        lastTime = now;
        Integer realBal = readRealBalance(tag);
        ui.post(() -> gate(id, realBal));
    }

    void gate(String id, Integer realBal) {
        lastCard = id;
        String r = realBal == null ? "" : "カードの本当の残高 " + realBal + "円（読むだけ）";
        boolean inside = prefs.getBoolean(id + ":in", false);
        int bal = prefs.getInt(id + ":bal", START_BALANCE);
        if (!inside) {
            if (bal < FARE) {
                pinpon();
                show(Color.rgb(200, 30, 30), "チャージしてね", "残高 " + bal + "円", r);
                return;
            }
            prefs.edit().putBoolean(id + ":in", true).putInt(id + ":bal", bal).apply();
            beep(new double[]{2000}, 0.12, 0);
            show(Color.rgb(0, 150, 70), "入場", "残高 " + bal + "円", r);
        } else {
            if (bal < FARE) {
                pinpon();
                show(Color.rgb(200, 30, 30), "残高不足", "あと " + (FARE - bal) + "円 チャージしてね", r);
                return;
            }
            bal -= FARE;
            prefs.edit().putBoolean(id + ":in", false).putInt(id + ":bal", bal).apply();
            beep(new double[]{2000, 2000}, 0.08, 0.06);
            show(Color.rgb(0, 150, 70), "ありがとう", "運賃 " + FARE + "円 ／ 残高 " + bal + "円", r);
        }
    }

    void show(int color, String m, String s, String r) {
        root.setBackgroundColor(color);
        main.setText(m); sub.setText(s); real.setText(r);
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(() -> idle(null), 4000);
    }

    void idle(String msg) {
        root.setBackgroundColor(Color.rgb(20, 40, 90));
        main.setText("タッチしてね");
        sub.setText(msg == null ? "Suica・PASMOをかざしてください" : msg);
        real.setText("");
    }

    /** 交通系ICの残高を読む（暗号なしで読める履歴の先頭から）。書き込みは一切しない。読めなければnull。 */
    Integer readRealBalance(Tag tag) {
        NfcF f = NfcF.get(tag);
        if (f == null) return null;
        try {
            f.connect();
            byte[] idm = tag.getId();
            byte[] cmd = new byte[16];
            cmd[0] = 16; cmd[1] = 0x06; // Read Without Encryption
            System.arraycopy(idm, 0, cmd, 2, 8);
            cmd[10] = 1; cmd[11] = 0x0F; cmd[12] = 0x09; // 履歴サービス 0x090F
            cmd[13] = 1; cmd[14] = (byte) 0x80; cmd[15] = 0;
            byte[] res = f.transceive(cmd);
            if (res.length < 29 || res[10] != 0) return null;
            return (res[13 + 10] & 0xFF) | ((res[13 + 11] & 0xFF) << 8);
        } catch (Exception e) {
            return null;
        } finally {
            try { f.close(); } catch (Exception ignored) { }
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02X", x));
        return s.toString();
    }

    void pinpon() {
        new Thread(() -> play(concat(tone(784, 0.45, true), tone(622, 0.7, true)))).start();
    }

    void beep(double[] freqs, double len, double gap) {
        new Thread(() -> {
            short[] all = new short[0];
            for (int i = 0; i < freqs.length; i++) {
                all = concat(all, tone(freqs[i], len, false));
                if (i < freqs.length - 1) all = concat(all, new short[(int) (44100 * gap)]);
            }
            play(all);
        }).start();
    }

    static short[] tone(double hz, double sec, boolean decay) {
        int n = (int) (44100 * sec);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double env = decay ? Math.exp(-3.0 * i / n) : Math.min(1, Math.min(i, n - i) / 200.0);
            s[i] = (short) (Math.sin(2 * Math.PI * hz * i / 44100) * env * 28000);
        }
        return s;
    }

    static short[] concat(short[] a, short[] b) {
        short[] c = new short[a.length + b.length];
        System.arraycopy(a, 0, c, 0, a.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }

    static void play(short[] pcm) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(44100)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.length * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
        t.write(pcm, 0, pcm.length);
        t.play();
        try { Thread.sleep(pcm.length * 1000L / 44100 + 100); } catch (InterruptedException ignored) { }
        t.release();
    }
}
