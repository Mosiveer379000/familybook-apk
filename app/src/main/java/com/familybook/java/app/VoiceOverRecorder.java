package com.familybook.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * ভয়েসওভার রেকর্ডার। একটাই WAV ফাইল (মনো, 44.1kHz, 16-bit) যার দৈর্ঘ্য = ভিডিওর পুরো দৈর্ঘ্য;
 * শুরুতে সবটা নীরব। প্রতিটি রেকর্ড ভিডিওর ঠিক যে সময়ে শুরু হয়েছিল ফাইলের সেই জায়গায় বসে যায়,
 * তাই এক্সপোর্টে আলাদা অফসেট/গ্যাপ হিসাব লাগে না — ফাইলটাই ভিডিওর সময়রেখার সাথে মেলানো।
 * Undo = ওই অংশ আবার নীরব করে দেওয়া।
 */
public class VoiceOverRecorder {

    public interface Callback { void onLimit(); }

    public static final int RATE = 44100;
    private static final int HEADER = 44;

    private final File file;
    private final long totalSamples;
    private final RandomAccessFile raf;
    private final List<long[]> ranges = new ArrayList<>();      // [startSample, endSample]
    private final Handler main = new Handler(Looper.getMainLooper());

    private AudioRecord rec;
    private NoiseSuppressor ns;
    private Thread th;
    private volatile boolean running;
    private long curStart;
    private volatile long curWritten;

    public VoiceOverRecorder(File f, long durMs) throws IOException {
        file = f;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        totalSamples = Math.max(1, durMs * RATE / 1000);
        raf = new RandomAccessFile(f, "rw");
        raf.setLength(0);
        long dataLen = totalSamples * 2;
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put(new byte[]{'R', 'I', 'F', 'F'}).putInt((int) (36 + dataLen))
                .put(new byte[]{'W', 'A', 'V', 'E'}).put(new byte[]{'f', 'm', 't', ' '})
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt((int) dataLen);
        raf.write(h.array());
        zeroFill(0, totalSamples);
    }

    public File getFile() { return file; }
    public boolean isRecording() { return running; }
    public int count() { return ranges.size(); }
    public boolean hasAny() { return !ranges.isEmpty(); }
    public long durationMs() { return totalSamples * 1000 / RATE; }

    /** রেকর্ড হওয়া অংশগুলো ms-এ: {startMs, endMs} */
    public List<long[]> rangesMs() {
        List<long[]> l = new ArrayList<>();
        for (long[] r : ranges) l.add(new long[]{r[0] * 1000 / RATE, r[1] * 1000 / RATE});
        return l;
    }

    /** মাইক পারমিশন থাকতে হবে। শুরু না হলে false। */
    @SuppressWarnings("MissingPermission")
    public synchronized boolean start(long startMs, final Callback cb) {
        if (running) return false;
        curStart = Math.min(totalSamples - 1, Math.max(0, startMs * RATE / 1000));
        curWritten = 0;
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, RATE / 5 * 2));
        } catch (Throwable t) { rec = null; return false; }
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) { releaseRec(); return false; }
        try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.getAudioSessionId()); } catch (Throwable ignored) { }
        try { rec.startRecording(); } catch (Throwable t) { releaseRec(); return false; }
        if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) { releaseRec(); return false; }

        running = true;
        final AudioRecord r = rec;
        th = new Thread(() -> {
            short[] buf = new short[2048];
            byte[] bb = new byte[buf.length * 2];
            boolean limit = false;
            try {
                while (running) {
                    int n = r.read(buf, 0, buf.length);
                    if (n < 0) break;
                    if (n == 0) continue;
                    long pos = curStart + curWritten;
                    int cnt = (int) Math.min(n, totalSamples - pos);
                    if (cnt > 0) {
                        for (int i = 0; i < cnt; i++) {
                            bb[2 * i] = (byte) (buf[i] & 0xFF);
                            bb[2 * i + 1] = (byte) ((buf[i] >> 8) & 0xFF);
                        }
                        synchronized (raf) {
                            raf.seek(HEADER + pos * 2);
                            raf.write(bb, 0, cnt * 2);
                        }
                        curWritten += cnt;
                    }
                    if (curStart + curWritten >= totalSamples) { limit = true; break; }
                }
            } catch (IOException ignored) { }
            final boolean hit = limit;
            if (hit && cb != null) main.post(cb::onLimit);
        }, "voiceover-rec");
        th.start();
        return true;
    }

    /** রেকর্ড বন্ধ; ০.১ সেকেন্ডের চেয়ে ছোট হলে বাতিল। */
    public void stop() {
        running = false;
        Thread t = th; th = null;
        if (t != null) { try { t.join(600); } catch (InterruptedException ignored) { } }
        releaseRec();
        long w = curWritten;
        curWritten = 0;
        if (w >= RATE / 10) ranges.add(new long[]{curStart, curStart + w});
        else if (w > 0) zeroFill(curStart, curStart + w);
    }

    /** শেষ রেকর্ডটা মুছে; ওটার শুরুর সময় (ms) ফেরত দেয়, না থাকলে -1। */
    public long undoLast() {
        if (ranges.isEmpty()) return -1;
        long[] r = ranges.remove(ranges.size() - 1);
        zeroFill(r[0], r[1]);
        return r[0] * 1000 / RATE;
    }

    public void truncateTo(int n) { while (ranges.size() > n) undoLast(); }

    public void release(boolean deleteFile) {
        if (running) stop();
        try { raf.close(); } catch (IOException ignored) { }
        if (deleteFile) //noinspection ResultOfMethodCallIgnored
            file.delete();
    }

    private void releaseRec() {
        try { if (ns != null) ns.release(); } catch (Throwable ignored) { }
        ns = null;
        AudioRecord r = rec; rec = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private void zeroFill(long fromSample, long toSample) {
        byte[] z = new byte[64 * 1024];
        try {
            synchronized (raf) {
                raf.seek(HEADER + fromSample * 2);
                long left = (toSample - fromSample) * 2;
                while (left > 0) {
                    int n = (int) Math.min(z.length, left);
                    raf.write(z, 0, n);
                    left -= n;
                }
            }
        } catch (IOException ignored) { }
    }
}
