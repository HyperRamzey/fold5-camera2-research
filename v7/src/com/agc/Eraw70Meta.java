package com.agc;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Builds the per-burst meta.json sidecar (v7 schema). */
final class Eraw70Meta {
    static final String TAG = Eraw70.TAG;
    static final String SCHEMA = "agc.fold5.50mp.raw10.v7";

    static void write(Eraw70.Runner r, Eraw70Imu imu, Eraw70Ois ois) throws Exception {
        if (r == null || r.dir == null) return;
        long writeStart = SystemClock.elapsedRealtimeNanos();
        JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("generator", "AGC v7.0 Eraw70");
        root.put("package", "com.samsung.android.ruler");
        root.put("camera_id", Eraw70.CAM_ID);
        root.put("device", Build.DEVICE);
        root.put("model", Build.MODEL);
        root.put("android_release", Build.VERSION.RELEASE);
        root.put("build_fingerprint", Build.FINGERPRINT);
        root.put("burst_dir", r.dir.getAbsolutePath());
        root.put("burst_epoch_ms", r.epoch);
        root.put("burst_start_elapsed_ns", r.tStart);
        if (r.error != null) root.put("error", r.error);

        ArrayList<FrameRec> fs = new ArrayList<FrameRec>();
        synchronized (r.frames) {
            fs.addAll(r.frames);
        }
        Collections.sort(fs, new Comparator<FrameRec>() {
            @Override
            public int compare(FrameRec a, FrameRec b) {
                return a.ts < b.ts ? -1 : (a.ts > b.ts ? 1 : 0);
            }
        });

        List<Eraw70Ois.VendorSample> vs = ois == null
                ? new ArrayList<Eraw70Ois.VendorSample>() : ois.snapshot();
        int baseX = 0, baseY = 0;
        if (!vs.isEmpty()) {
            baseX = vs.get(0).v[4];
            baseY = vs.get(0).v[5];
        }
        JSONArray vendorAll = new JSONArray();
        for (int i = 0; i < vs.size(); i++) {
            vendorAll.put(vendorJson(vs.get(i), baseX, baseY));
        }

        long winStart = 0, winEnd = 0;
        long skew0 = 0, skew5 = 0;
        boolean haveWindow = !fs.isEmpty();
        if (haveWindow) {
            FrameRec first = fs.get(0);
            FrameRec last = fs.get(fs.size() - 1);
            Long k0 = skewOf(r, first);
            Long k5 = skewOf(r, last);
            skew0 = k0 == null ? 0 : k0.longValue();
            skew5 = k5 == null ? 0 : k5.longValue();
            winStart = first.ts - 50000000L;
            winEnd = last.ts + skew5 + 50000000L;
        }

        JSONArray frames = new JSONArray();
        int stdTotal = 0;
        int vendorAssigned = 0;
        for (int i = 0; i < fs.size(); i++) {
            FrameRec fr = fs.get(i);
            JSONObject fj = frameJson(r, fr, vs, baseX, baseY);
            if (fj.optInt("ois_standard_count", 0) > 0) stdTotal += fj.optInt("ois_standard_count", 0);
            if (fj.optInt("ois_vendor_count", 0) > 0) vendorAssigned++;
            fj.remove("ois_standard_count");
            fj.remove("ois_vendor_count");
            frames.put(fj);
        }
        root.put("frames", frames);
        root.put("frames_count", fs.size());

        root.put("raw", rawJson(fs));
        root.put("static_info", staticInfo(r.chars));

        JSONObject oisObj = new JSONObject();
        oisObj.put("standard_request_attempted", r.stdAttempted);
        oisObj.put("standard_builder_accepted", r.stdAccepted);
        oisObj.put("standard_preview_samples_count", sizeOf(r.previewOis, r));
        JSONArray pv = new JSONArray();
        synchronized (r.previewOis) {
            for (int i = 0; i < r.previewOis.size() && i < 500; i++) {
                pv.put(stdOisJson(r.previewOis.get(i)));
            }
        }
        oisObj.put("standard_preview_samples", pv);
        oisObj.put("standard_samples_assigned_to_frames", stdTotal);
        oisObj.put("vendor_node", Eraw70Ois.NODE);
        oisObj.put("vendor_direct_read_ok", ois != null && ois.directUsed);
        oisObj.put("vendor_root_used", ois != null && ois.rootUsed);
        oisObj.put("vendor_error", ois == null || ois.error == null ? JSONObject.NULL : ois.error);
        oisObj.put("vendor_parse_fails", ois == null ? -1 : ois.parseFails);
        oisObj.put("vendor_samples_count", vs.size());
        oisObj.put("vendor_samples_assigned_to_frames", vendorAssigned);
        oisObj.put("vendor_units", "hall_adc_uncalibrated");
        oisObj.put("vendor_module_count_from_kernel", 2);
        oisObj.put("dx_px_note",
                "dx_px/dy_px carry raw hall ADC deltas (hall_x-base_x). "
                + "unit=hall_adc_uncalibrated, calibrated=false; no px scale was invented. "
                + "Module 1 is the main wide OIS; module 2 is included per sample.");
        oisObj.put("vendor_samples", vendorAll);
        oisObj.put("vendor_exif", safe(ois == null ? null : ois.exif));
        oisObj.put("vendor_gain_rear", safe(ois == null ? null : ois.gain));
        oisObj.put("vendor_rawdata", safe(ois == null ? null : ois.raw));
        oisObj.put("vendor_adc", safe(ois == null ? null : ois.adc));
        root.put("ois", oisObj);

        ArrayList<Eraw70Imu.ImuSample> gyro = imu == null
                ? new ArrayList<Eraw70Imu.ImuSample>() : new ArrayList<Eraw70Imu.ImuSample>(imu.snapshot(imu.gyro));
        ArrayList<Eraw70Imu.ImuSample> accel = imu == null
                ? new ArrayList<Eraw70Imu.ImuSample>() : new ArrayList<Eraw70Imu.ImuSample>(imu.snapshot(imu.accel));
        ArrayList<Eraw70Imu.ImuSample> game = imu == null
                ? new ArrayList<Eraw70Imu.ImuSample>() : new ArrayList<Eraw70Imu.ImuSample>(imu.snapshot(imu.game));
        JSONArray gyroJ = imuJson(gyro, haveWindow ? winStart : Long.MIN_VALUE,
                haveWindow ? winEnd : Long.MAX_VALUE, 0);
        JSONArray accelJ = imuJson(accel, haveWindow ? winStart : Long.MIN_VALUE,
                haveWindow ? winEnd : Long.MAX_VALUE, 1);
        JSONArray gameJ = imuJson(game, haveWindow ? winStart : Long.MIN_VALUE,
                haveWindow ? winEnd : Long.MAX_VALUE, 2);
        root.put("imu_gyro_uncal", gyroJ);
        root.put("imu_accel_uncal", accelJ);
        root.put("imu_game_rotation", gameJ);
        JSONObject ic = new JSONObject();
        ic.put("gyro_total", gyro.size());
        ic.put("gyro_in_window", gyroJ.length());
        ic.put("accel_total", accel.size());
        ic.put("accel_in_window", accelJ.length());
        ic.put("game_total", game.size());
        ic.put("game_in_window", gameJ.length());
        ic.put("gyro_rate_hz", rateHz(gyro));
        root.put("imu_counts", ic);
        if (haveWindow) {
            JSONArray iw = new JSONArray();
            iw.put(winStart);
            iw.put(winEnd);
            root.put("imu_window_ns", iw);
        }
        root.put("ae_awb_locked", true);
        root.put("preview_settle_count", r.settlePreviews);

        JSONObject tm = new JSONObject();
        tm.put("setup_ms", ms(r.tSessionReady - r.tStart));
        tm.put("settle_ms", ms(r.tBurstSubmit - r.tSessionReady));
        tm.put("capture_ms", ms(r.tLastImage - r.tBurstSubmit));
        tm.put("frame_write_span_ms", ms(r.tLastImage - r.tFirstImage));
        tm.put("meta_ms", 0);
        tm.put("tap_to_disk_ms", 0);
        tm.put("prepare_ms", ms(r.tBurstSubmit - r.tStart));
        tm.put("total_ms", ms(SystemClock.elapsedRealtimeNanos() - r.tStart));
        root.put("timings", tm);
        root.put("rolling_shutter_skew_ns", firstSkew(r, fs));
        root.put("rolling_shutter_skew_source", skewSource(r, fs));

        writeFile(r.dir, root.toString(2));
        long tMeta1 = SystemClock.elapsedRealtimeNanos();
        r.tMeta = tMeta1;
        tm.put("meta_ms", ms(tMeta1 - writeStart));
        // The shutter tap is the burst submission; everything before it is
        // session warm-up done while the user is still framing the shot.
        tm.put("tap_to_disk_ms", ms(tMeta1 - r.tBurstSubmit));
        tm.put("total_ms", ms(tMeta1 - r.tStart));
        writeFile(r.dir, root.toString(2));
        Log.i(TAG, "meta written " + new File(r.dir, "meta.json").length()
                + "B total=" + tm.optDouble("total_ms") + "ms frames=" + fs.size()
                + " vendorOis=" + vs.size() + " gyro=" + gyro.size());
    }

    static void writeFile(File dir, String s) throws Exception {
        FileOutputStream fos = new FileOutputStream(new File(dir, "meta.json"));
        try {
            fos.write(s.getBytes("UTF-8"));
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }

    static JSONObject rawJson(List<FrameRec> fs) throws Exception {
        JSONObject o = new JSONObject();
        o.put("width", Eraw70.W);
        o.put("height", Eraw70.H);
        o.put("format", "RAW10");
        o.put("format_id", 37);
        o.put("packing", "MIPI RAW10: 5 bytes per 4 pixels, low 2 bits in byte 4");
        o.put("cfa", "GBRG");
        o.put("black_level", 256);
        o.put("white_level", 4095);
        if (!fs.isEmpty()) {
            FrameRec f = fs.get(0);
            o.put("row_stride", f.rowStride);
            o.put("pixel_stride", f.pixelStride);
            o.put("expected_bytes", (long) f.rowStride * (long) f.height);
        }
        o.put("frame_count_expected", Eraw70.N);
        o.put("frame_count_actual", fs.size());
        return o;
    }

    static JSONObject frameJson(Eraw70.Runner r, FrameRec fr,
                                List<Eraw70Ois.VendorSample> vs, int baseX, int baseY) throws Exception {
        JSONObject f = new JSONObject();
        f.put("idx", fr.index);
        f.put("file", fr.file);
        f.put("bytes", fr.bytes);
        f.put("row_stride", fr.rowStride);
        f.put("pixel_stride", fr.pixelStride);
        f.put("width", fr.width);
        f.put("height", fr.height);
        f.put("sensor_timestamp_ns", fr.ts);
        f.put("write_ms", fr.writeMs);
        f.put("arrival_ms", fr.arrivalMs);
        TotalCaptureResult res = fr.result;
        Long skew = null;
        String skewSrc = "unavailable";
        int stdCount = 0;
        int vendorCount = 0;
        JSONArray oisArr = new JSONArray();
        if (res != null) {
            f.put("frame_number", res.getFrameNumber());
            putLong(f, "SENSOR_EXPOSURE_TIME", res.get(CaptureResult.SENSOR_EXPOSURE_TIME));
            putInt(f, "SENSOR_SENSITIVITY", res.get(CaptureResult.SENSOR_SENSITIVITY));
            skew = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (skew != null) skewSrc = "result";
            putFloat(f, "LENS_FOCUS_DISTANCE", res.get(CaptureResult.LENS_FOCUS_DISTANCE));
            putInt(f, "LENS_STATE", res.get(CaptureResult.LENS_STATE));
            putInt(f, "CONTROL_AF_STATE", res.get(CaptureResult.CONTROL_AF_STATE));
            putInt(f, "CONTROL_AE_STATE", res.get(CaptureResult.CONTROL_AE_STATE));
            putInt(f, "CONTROL_AWB_STATE", res.get(CaptureResult.CONTROL_AWB_STATE));
            RggbChannelVector g = res.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (g != null) {
                JSONArray a = new JSONArray();
                a.put((double) g.getRed());
                a.put((double) g.getGreenEven());
                a.put((double) g.getBlue());
                a.put((double) g.getGreenOdd());
                f.put("COLOR_CORRECTION_GAINS", a);
                f.put("COLOR_CORRECTION_GAINS_ORDER", new JSONArray(
                        new String[]{"R", "G_EVEN", "B", "G_ODD"}));
            }
            LensShadingMap m = res.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
            if (m != null) {
                try {
                    f.put("STATISTICS_LENS_SHADING_CORRECTION_MAP", lscJson(m));
                } catch (Throwable t) {
                    // Never lose the whole meta.json over one optional block.
                    f.put("STATISTICS_LENS_SHADING_CORRECTION_MAP", JSONObject.NULL);
                    f.put("lens_shading_error", String.valueOf(t));
                }
            }
            OisSample[] os = res.get(CaptureResult.STATISTICS_OIS_SAMPLES);
            if (os != null) {
                for (int i = 0; i < os.length; i++) {
                    oisArr.put(stdOisJson(os[i]));
                }
                stdCount = os.length;
            }
        }
        if (skew == null && r.previewSkew != null) {
            skew = r.previewSkew;
            skewSrc = "preview_result";
        }
        if (skew == null) {
            Long s = staticSkew(r.chars);
            if (s != null) {
                skew = s;
                skewSrc = "static";
            }
        }
        f.put("rolling_shutter_skew_ns", skew == null ? JSONObject.NULL : skew.longValue());
        f.put("rolling_shutter_skew_source", skewSrc);
        if (!vs.isEmpty()) {
            Eraw70Ois.VendorSample best = null;
            long bd = Long.MAX_VALUE;
            for (int i = 0; i < vs.size(); i++) {
                long d = Math.abs(vs.get(i).t - fr.ts);
                if (d < bd) {
                    bd = d;
                    best = vs.get(i);
                }
            }
            if (best != null && bd <= 500000000L) {
                oisArr.put(vendorJson(best, baseX, baseY));
                vendorCount = 1;
            }
        }
        f.put("ois_samples", oisArr);
        f.put("ois_source", stdCount > 0
                ? (vendorCount > 0 ? "standard+vendor_sysfs" : "standard")
                : (vendorCount > 0 ? "vendor_sysfs" : "none"));
        f.put("ois_standard_count", stdCount);
        f.put("ois_vendor_count", vendorCount);
        return f;
    }

    static JSONObject stdOisJson(OisSample s) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t_ns", s.getTimestamp());
        o.put("dx_px", (double) s.getXshift());
        o.put("dy_px", (double) s.getYshift());
        o.put("unit", "pixel");
        o.put("source", "android.statistics.oisSamples");
        return o;
    }

    static JSONObject vendorJson(Eraw70Ois.VendorSample s, int baseX, int baseY) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t_ns", s.t);
        o.put("module", 1);
        o.put("dx_px", s.v[4] - baseX);
        o.put("dy_px", s.v[5] - baseY);
        o.put("hall_dx", s.v[4] - baseX);
        o.put("hall_dy", s.v[5] - baseY);
        o.put("hall_x", s.v[4]);
        o.put("hall_y", s.v[5]);
        o.put("target_x", s.v[0]);
        o.put("target_y", s.v[1]);
        o.put("unit", "hall_adc_uncalibrated");
        o.put("calibrated", false);
        o.put("source", "sysfs:" + Eraw70Ois.NODE);
        JSONObject m2 = new JSONObject();
        m2.put("hall_x", s.v[6]);
        m2.put("hall_y", s.v[7]);
        m2.put("target_x", s.v[2]);
        m2.put("target_y", s.v[3]);
        o.put("module2", m2);
        return o;
    }

    static JSONObject lscJson(LensShadingMap m) throws Exception {
        JSONObject o = new JSONObject();
        int rows = m.getRowCount();
        int cols = m.getColumnCount();
        o.put("rows", rows);
        o.put("columns", cols);
        o.put("channels", 4);
        o.put("channel_order", new JSONArray(new String[]{"R", "G_EVEN", "B", "G_ODD"}));
        JSONArray ch = new JSONArray();
        for (int c = 0; c < 4; c++) {
            JSONArray a = new JSONArray();
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    a.put((double) m.getGainFactor(c, col, row));
                }
            }
            ch.put(a);
        }
        o.put("gains_channel_major", ch);
        return o;
    }

    static JSONObject staticInfo(CameraCharacteristics c) throws Exception {
        JSONObject st = new JSONObject();
        st.put("available", c != null);
        if (c == null) return st;
        try {
            putRect(st, "SENSOR_INFO_ACTIVE_ARRAY_SIZE",
                    c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE));
            putRect(st, "SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE",
                    c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE));
            putSize(st, "SENSOR_INFO_PIXEL_ARRAY_SIZE",
                    c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE));
            SizeF ps = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            if (ps != null) {
                JSONArray a = new JSONArray();
                a.put((double) ps.getWidth());
                a.put((double) ps.getHeight());
                st.put("SENSOR_INFO_PHYSICAL_SIZE", a);
            }
            Integer ts = c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            if (ts != null) {
                st.put("SENSOR_INFO_TIMESTAMP_SOURCE", ts.intValue());
                st.put("SENSOR_INFO_TIMESTAMP_SOURCE_NAME", tsName(ts.intValue()));
            }
            Integer cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            if (cfa != null) {
                st.put("SENSOR_INFO_COLOR_FILTER_ARRANGEMENT", cfa.intValue());
                st.put("cfa", cfaName(cfa.intValue()));
            }
            Integer wl = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            if (wl != null) st.put("SENSOR_INFO_WHITE_LEVEL", wl.intValue());
            BlackLevelPattern bl = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            if (bl != null) {
                int[] b = new int[4];
                for (int i = 0; i < 4; i++) b[i] = bl.getOffsetForIndex(i % 2, i / 2);
                JSONArray ba = new JSONArray();
                for (int i = 0; i < 4; i++) ba.put(b[i]);
                st.put("SENSOR_BLACK_LEVEL_PATTERN", ba);
                st.put("black_level", b[0]);
            }
            putFloats(st, "LENS_INTRINSIC_CALIBRATION",
                    c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION));
            putFloats(st, "LENS_RADIAL_DISTORTION",
                    c.get(CameraCharacteristics.LENS_RADIAL_DISTORTION));
            putFloats(st, "LENS_POSE_ROTATION",
                    c.get(CameraCharacteristics.LENS_POSE_ROTATION));
            putFloats(st, "LENS_POSE_TRANSLATION",
                    c.get(CameraCharacteristics.LENS_POSE_TRANSLATION));
            st.put("SENSOR_ROLLING_SHUTTER_SKEW_NS", JSONObject.NULL);
            st.put("SENSOR_ROLLING_SHUTTER_SKEW_NOTE",
                    "not exposed by CameraCharacteristics on this HAL; read per-frame from CaptureResult");
            int[] oisModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
            if (oisModes != null) {
                JSONArray oa = new JSONArray();
                for (int i = 0; i < oisModes.length; i++) oa.put(oisModes[i]);
                st.put("STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES", oa);
            }
            Integer readout = c.get(CameraCharacteristics.SENSOR_READOUT_TIMESTAMP);
            if (readout != null) st.put("SENSOR_READOUT_TIMESTAMP", readout.intValue());
            Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (orient != null) st.put("SENSOR_ORIENTATION", orient.intValue());
            float[] in = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
            if (in != null && in.length >= 4) {
                JSONArray a = new JSONArray();
                a.put((double) (in[0] * 2.0f));
                a.put((double) (in[1] * 2.0f));
                a.put((double) (in[2] * 2.0f + 0.5f));
                a.put((double) (in[3] * 2.0f + 0.5f));
                a.put(in.length > 4 ? (double) in[4] : 0.0d);
                st.put("LENS_INTRINSIC_CALIBRATION_FULLRES_DERIVED", a);
                st.put("LENS_INTRINSIC_CALIBRATION_FULLRES_NOTE",
                        "derived x2 from the binned 4080x3060 calibration; center +0.5 px for the 2x2 bin block");
            }
            st.put("raw_frame_size", new JSONArray(new int[]{Eraw70.W, Eraw70.H}));
            st.put("static_active_array_note",
                    "camera 56 static reports the binned 4080x3060 array; the HighResolution RAW10 stream is 8160x6120");
        } catch (Throwable t) {
            st.put("error", String.valueOf(t));
        }
        return st;
    }

    static JSONArray imuJson(List<Eraw70Imu.ImuSample> list, long a, long b, int kind)
            throws Exception {
        JSONArray arr = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Eraw70Imu.ImuSample s = list.get(i);
            if (s.t < a || s.t > b) continue;
            JSONObject o = new JSONObject();
            o.put("t_ns", s.t);
            if (kind == 0) {
                o.put("wx", (double) s.v[0]);
                o.put("wy", (double) s.v[1]);
                o.put("wz", (double) s.v[2]);
                if (s.v.length >= 6) {
                    o.put("bx", (double) s.v[3]);
                    o.put("by", (double) s.v[4]);
                    o.put("bz", (double) s.v[5]);
                }
            } else if (kind == 1) {
                o.put("ax", (double) s.v[0]);
                o.put("ay", (double) s.v[1]);
                o.put("az", (double) s.v[2]);
                if (s.v.length >= 6) {
                    o.put("bx", (double) s.v[3]);
                    o.put("by", (double) s.v[4]);
                    o.put("bz", (double) s.v[5]);
                }
            } else {
                o.put("x", (double) s.v[0]);
                o.put("y", (double) s.v[1]);
                o.put("z", (double) s.v[2]);
                o.put("w", s.v.length > 3 ? (double) s.v[3] : 1.0d);
            }
            arr.put(o);
        }
        return arr;
    }

    static Long skewOf(Eraw70.Runner r, FrameRec fr) {
        if (fr.result != null) {
            Long s = fr.result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (s != null) return s;
        }
        if (r.previewSkew != null) return r.previewSkew;
        return staticSkew(r.chars);
    }

    static Object firstSkew(Eraw70.Runner r, List<FrameRec> fs) {
        for (int i = 0; i < fs.size(); i++) {
            Long s = skewOf(r, fs.get(i));
            if (s != null) return Long.valueOf(s.longValue());
        }
        return JSONObject.NULL;
    }

    static String skewSource(Eraw70.Runner r, List<FrameRec> fs) {
        for (int i = 0; i < fs.size(); i++) {
            FrameRec fr = fs.get(i);
            if (fr.result != null && fr.result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW) != null)
                return "result";
        }
        if (r.previewSkew != null) return "preview_result";
        if (staticSkew(r.chars) != null) return "static";
        return "unavailable";
    }

    static Long staticSkew(CameraCharacteristics c) {
        // Android exposes no static rolling-shutter-skew key; the HAL may
        // still populate CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW per frame.
        return null;
    }

    static int sizeOf(List<?> l, Eraw70.Runner r) {
        synchronized (r.previewOis) {
            return r.previewOis.size();
        }
    }

    static double rateHz(List<Eraw70Imu.ImuSample> l) {
        if (l.size() < 2) return 0.0d;
        long dt = l.get(l.size() - 1).t - l.get(0).t;
        if (dt <= 0) return 0.0d;
        return (l.size() - 1) * 1.0e9d / dt;
    }

    static double ms(long ns) {
        return ns / 1000000.0d;
    }

    static Object safe(String s) {
        return s == null ? JSONObject.NULL : s;
    }

    static void putLong(JSONObject o, String k, Long v) throws Exception {
        o.put(k, v == null ? JSONObject.NULL : v.longValue());
    }

    static void putInt(JSONObject o, String k, Integer v) throws Exception {
        o.put(k, v == null ? JSONObject.NULL : v.intValue());
    }

    static void putFloat(JSONObject o, String k, Float v) throws Exception {
        o.put(k, (v == null || v.isNaN() || v.isInfinite()) ? JSONObject.NULL : (double) v.floatValue());
    }

    static void putFloats(JSONObject o, String k, float[] a) throws Exception {
        if (a == null) {
            o.put(k, JSONObject.NULL);
            return;
        }
        JSONArray arr = new JSONArray();
        for (int i = 0; i < a.length; i++) {
            if (Float.isNaN(a[i]) || Float.isInfinite(a[i])) arr.put(JSONObject.NULL);
            else arr.put((double) a[i]);
        }
        o.put(k, arr);
    }

    static void putRect(JSONObject o, String k, Rect r) throws Exception {
        if (r == null) {
            o.put(k, JSONObject.NULL);
            return;
        }
        JSONArray a = new JSONArray();
        a.put(r.left);
        a.put(r.top);
        a.put(r.right);
        a.put(r.bottom);
        o.put(k, a);
    }

    static void putSize(JSONObject o, String k, Size s) throws Exception {
        if (s == null) {
            o.put(k, JSONObject.NULL);
            return;
        }
        JSONArray a = new JSONArray();
        a.put(s.getWidth());
        a.put(s.getHeight());
        o.put(k, a);
    }

    static String cfaName(int v) {
        switch (v) {
            case 0: return "RGGB";
            case 1: return "GRBG";
            case 2: return "GBRG";
            case 3: return "BGGR";
            default: return "UNKNOWN_" + v;
        }
    }

    static String tsName(int v) {
        switch (v) {
            case 0: return "UNKNOWN";
            case 1: return "REALTIME";
            case 2: return "MONOTONIC";
            default: return "UNKNOWN_" + v;
        }
    }

    static final class FrameRec {
        int index;
        int width;
        int height;
        int rowStride;
        int pixelStride;
        long ts;
        long bytes;
        String file;
        double writeMs;
        double arrivalMs;
        TotalCaptureResult result;
    }
}
