package com.familybook.app;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/**
 * "Enhance" (আওয়াজ পরিষ্কার): ভিডিওর মূল অডিও থেকে
 *  ১) গুমগুম/বাতাসের নিচু শব্দ (≈110Hz-এর নিচে) ছাঁটে (high-pass), আর
 *  ২) কথা না থাকা অংশের ব্যাকগ্রাউন্ড হিস কমায় (noise gate/expander)।
 * strength 0..1 — যত বেশি, তত কড়া। শুধু 16-bit PCM-এ কাজ করে (Transformer-এ সেটাই আসে)।
 */
@OptIn(markerClass = UnstableApi.class)
public final class NoiseCleanAudioProcessor extends BaseAudioProcessor {

    private final float strength;
    private int ch = 2;
    private float hpA;
    private float[] px = new float[2], py = new float[2], tmp = new float[2];
    private float env = 0f, gain = 1f;
    private float thr, atk, rel, gDown, gUp, floorGain;

    public NoiseCleanAudioProcessor(float strength) {
        this.strength = Math.max(0f, Math.min(1f, strength));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat in) throws UnhandledAudioFormatException {
        if (in.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(in);
        ch = Math.max(1, in.channelCount);
        px = new float[ch]; py = new float[ch]; tmp = new float[ch];
        float rate = in.sampleRate;
        float rc = 1f / (float) (2 * Math.PI * 110f);
        hpA = rc / (rc + 1f / rate);
        thr = 0.006f + 0.03f * strength;                       // ≈ -44dB .. -29dB
        floorGain = 1f - 0.9f * strength;                      // সবচেয়ে বেশি কমবে ≈ -20dB
        atk = 1f - (float) Math.exp(-1.0 / (0.004 * rate));
        rel = 1f - (float) Math.exp(-1.0 / (0.15 * rate));
        gDown = 1f - (float) Math.exp(-1.0 / (0.05 * rate));
        gUp = 1f - (float) Math.exp(-1.0 / (0.005 * rate));
        env = 0f; gain = 1f;
        return in;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        int frames = input.remaining() / (2 * ch);
        if (frames == 0) { input.position(input.limit()); return; }
        ByteBuffer out = replaceOutputBuffer(frames * 2 * ch);
        for (int f = 0; f < frames; f++) {
            float peak = 0f;
            for (int c = 0; c < ch; c++) {
                float x = input.getShort() / 32768f;
                float y = hpA * (py[c] + x - px[c]);
                px[c] = x; py[c] = y; tmp[c] = y;
                float a = y < 0 ? -y : y;
                if (a > peak) peak = a;
            }
            env += (peak - env) * (peak > env ? atk : rel);
            float target = 1f;
            if (env < thr) { float r = env / thr; target = Math.max(floorGain, r * r); }
            gain += (target - gain) * (target < gain ? gDown : gUp);
            for (int c = 0; c < ch; c++) {
                float v = tmp[c] * gain * 32768f;
                if (v > 32767f) v = 32767f; else if (v < -32768f) v = -32768f;
                out.putShort((short) v);
            }
        }
        input.position(input.limit());
        out.flip();
    }
}
