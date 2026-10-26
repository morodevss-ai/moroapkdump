package com.wolfdumper;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ── paths ───────────────────────────────────────────────────────────────
    private static final String BINARY_PATH   = "/data/local/tmp/wolfdumper";
    private static final String DOTNET_PATH   = "/data/data/com.termux/files/usr/bin/dotnet";
    private static final String IL2CPP_DLL    = "/data/local/tmp/Il2CppDumper/Il2CppDumper.dll";
    private static final String PKG_FF        = "com.dts.freefireth";
    private static final String PKG_FFMAX     = "com.dts.freefiremax";
    private static final String OUT_ROOT      = "/sdcard/WolfDumper";

    // ── UI ──────────────────────────────────────────────────────────────────
    private TextView    tvRootStatus, tvDotnet, tvIl2cpp, tvTarget, tvStage, tvPct, tvConsole;
    private ProgressBar progressBar;
    private ScrollView  scrollConsole;
    private MaterialButton btnDump, btnExport;

    // ── state ───────────────────────────────────────────────────────────────
    private final Handler   handler   = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private boolean isRunning   = false;
    private boolean isRooted    = false;
    private String  selectedPkg = PKG_FF;
    private String  lastOutDir  = null;

    // ────────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvRootStatus = findViewById(R.id.tvRootStatus);
        tvDotnet     = findViewById(R.id.tvDotnet);
        tvIl2cpp     = findViewById(R.id.tvIl2cpp);
        tvTarget     = findViewById(R.id.tvTarget);
        tvStage      = findViewById(R.id.tvStage);
        tvPct        = findViewById(R.id.tvPct);
        tvConsole    = findViewById(R.id.tvConsole);
        progressBar  = findViewById(R.id.progressBar);
        scrollConsole= findViewById(R.id.scrollConsole);
        btnDump      = findViewById(R.id.btnDump);
        btnExport    = findViewById(R.id.btnExport);

        MaterialButton btnFF    = findViewById(R.id.btnFF);
        MaterialButton btnFFMax = findViewById(R.id.btnFFMax);

        btnFF.setOnClickListener(v -> selectPkg(PKG_FF, btnFF, btnFFMax));
        btnFFMax.setOnClickListener(v -> selectPkg(PKG_FFMAX, btnFFMax, btnFF));

        btnDump.setOnClickListener(v -> {
            if (!isRunning) startDump();
        });
        btnExport.setOnClickListener(v -> {
            if (lastOutDir != null) exportResult(lastOutDir);
        });

        requestPermissions();
        pool.execute(this::runStartupChecks);
    }

    // ── package selector ────────────────────────────────────────────────────
    private void selectPkg(String pkg, MaterialButton active, MaterialButton inactive) {
        selectedPkg = pkg;
        active.setStrokeColor(
                ContextCompat.getColorStateList(this, R.color.accent));
        active.setTextColor(ContextCompat.getColor(this, R.color.accent));
        inactive.setStrokeColor(
                ContextCompat.getColorStateList(this, R.color.divider));
        inactive.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvTarget.setText(pkg.equals(PKG_FF) ? "FF" : "FFMAX");
        tvTarget.setTextColor(ContextCompat.getColor(this, R.color.green));
    }

    // ── startup checks ──────────────────────────────────────────────────────
    private void runStartupChecks() {
        log("info", "Running startup checks...");

        // root
        isRooted = checkRoot();
        ui(() -> {
            if (isRooted) {
                tvRootStatus.setText("⬤ ROOT");
                tvRootStatus.setTextColor(ContextCompat.getColor(this, R.color.green));
            } else {
                tvRootStatus.setText("⬤ NO ROOT");
                tvRootStatus.setTextColor(ContextCompat.getColor(this, R.color.red));
                log("error", "ROOT NOT FOUND — grant root to Termux, then restart.");
            }
        });

        // ── dotnet detection ────────────────────────────────────────────────
        log("info", "Checking dotnet...");
        boolean dotnetOk = false;
        String  dotnetVer = "";

        // check common Termux dotnet paths
        String[] dotnetCandidates = {
            "/data/data/com.termux/files/usr/bin/dotnet",
            "/data/data/com.termux/files/usr/local/bin/dotnet",
        };
        String foundDotnet = null;
        for (String c : dotnetCandidates) {
            if (new File(c).exists()) { foundDotnet = c; break; }
        }

        if (foundDotnet != null) {
            // get version
            try {
                Process pv = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", foundDotnet + " --version"});
                BufferedReader bv = new BufferedReader(
                        new InputStreamReader(pv.getInputStream()));
                String vline = bv.readLine();
                pv.waitFor();
                if (vline != null) dotnetVer = vline.trim();
            } catch (Exception ignored) {}
            dotnetOk = true;
            final String fv = dotnetVer;
            final String fp = foundDotnet;
            ui(() -> {
                tvDotnet.setText("✓ " + (fv.isEmpty() ? "OK" : fv));
                tvDotnet.setTextColor(ContextCompat.getColor(this, R.color.green));
            });
            log("ok", "dotnet found: " + foundDotnet);
            log("ok", "dotnet version: " + (dotnetVer.isEmpty() ? "unknown" : dotnetVer));
        } else {
            ui(() -> {
                tvDotnet.setText("✗ Missing");
                tvDotnet.setTextColor(ContextCompat.getColor(this, R.color.red));
            });
            log("warn", "dotnet NOT found in Termux paths");
            log("warn", "Install in Termux: pkg install dotnet-sdk");
        }

        // ── Il2CppDumper detection + auto-download ──────────────────────────
        log("info", "Checking Il2CppDumper...");
        boolean il2cppOk = new File(IL2CPP_DLL).exists();

        if (il2cppOk) {
            long sz = new File(IL2CPP_DLL).length();
            ui(() -> {
                tvIl2cpp.setText("✓ OK");
                tvIl2cpp.setTextColor(ContextCompat.getColor(this, R.color.green));
            });
            log("ok", "Il2CppDumper.dll found (" + String.format(Locale.US, "%.0f", sz/1024.0) + " KB)");
        } else {
            ui(() -> {
                tvIl2cpp.setText("⬇ Downloading...");
                tvIl2cpp.setTextColor(ContextCompat.getColor(this, R.color.yellow));
            });
            log("warn", "Il2CppDumper.dll not found — auto-downloading...");
            il2cppOk = downloadIl2CppDumper();
            if (il2cppOk) {
                ui(() -> {
                    tvIl2cpp.setText("✓ Downloaded");
                    tvIl2cpp.setTextColor(ContextCompat.getColor(this, R.color.green));
                });
                log("ok", "Il2CppDumper downloaded successfully");
            } else {
                ui(() -> {
                    tvIl2cpp.setText("✗ Failed");
                    tvIl2cpp.setTextColor(ContextCompat.getColor(this, R.color.red));
                });
                log("error", "Il2CppDumper download failed — check internet");
            }
        }

        // ── wolfdumper binary ───────────────────────────────────────────────
        boolean binOk = new File(BINARY_PATH).exists();
        if (!binOk) log("error", "wolfdumper binary not found at " + BINARY_PATH);
        else        log("ok",    "wolfdumper binary found");

        // ── parser summary ──────────────────────────────────────────────────
        if (dotnetOk && il2cppOk)
            log("ok", "Parser: Il2CppDumper (dotnet " + dotnetVer + ") — FAST <1 min");
        else
            log("warn", "Parser: Python fallback or not configured");

        // detect game
        pool.execute(() -> {
            String pid = findPid(selectedPkg);
            if (pid != null) {
                log("ok", selectedPkg + " running (PID " + pid + ")");
                ui(() -> {
                    tvTarget.setText(selectedPkg.equals(PKG_FF) ? "FF ✓" : "FFMAX ✓");
                    tvTarget.setTextColor(ContextCompat.getColor(this, R.color.green));
                });
            } else {
                log("warn", "Game not running — launch it before dumping");
            }
        });

        log("info", "Checks done. Press FULL DUMP to start.");
    }

    // ── root check ──────────────────────────────────────────────────────────
    private boolean checkRoot() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Exception e) {
            return false;
        }
    }

    // ── find pid ────────────────────────────────────────────────────────────
    private String findPid(String pkg) {
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "pidof " + pkg});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) return line.trim().split("\\s+")[0];
        } catch (Exception ignored) {}
        return null;
    }

    // ── main dump ───────────────────────────────────────────────────────────
    private void startDump() {
        if (!isRooted) {
            Toast.makeText(this, "Root required!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!new File(BINARY_PATH).exists()) {
            log("error", "wolfdumper binary missing: " + BINARY_PATH);
            Toast.makeText(this, "Binary not found!", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        lastOutDir = null;
        tvConsole.setText("");
        setProgress(0, "Starting...");
        btnDump.setText("⏳  RUNNING...");
        btnDump.setEnabled(false);
        btnExport.setEnabled(false);

        pool.execute(() -> {
            try {
                runWolfDumper();
            } finally {
                ui(() -> {
                    isRunning = false;
                    btnDump.setText("▶  FULL DUMP");
                    btnDump.setEnabled(true);
                });
            }
        });
    }

    private void runWolfDumper() {
        try {
            log("info", "══════════════════════════════════");
            log("info", " WolfDumper v3.0 starting...");
            log("info", " Target: " + selectedPkg);
            log("info", "══════════════════════════════════");

            setProgress(5, "Checking root...");
            if (!checkRoot()) {
                log("error", "Root check failed — aborting.");
                return;
            }
            log("ok", "Root confirmed");

            setProgress(10, "Finding game PID...");
            String pid = findPid(selectedPkg);
            if (pid == null) {
                log("error", "Game not running — launch " + selectedPkg + " first.");
                return;
            }
            log("ok", "Found PID: " + pid);

            setProgress(15, "Preparing output dir...");
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String outDir = OUT_ROOT + "/" + selectedPkg + "_" + ts;
            new File(outDir).mkdirs();
            log("info", "Session dir: " + outDir);

            setProgress(20, "Checking disk copy...");
            String diskLib = findDiskLib(selectedPkg);
            String libPath;
            long libBase = getLibBase(selectedPkg);

            String baseHex = String.format("(0x%X)", libBase);
            libPath = outDir + "/" + baseHex + "libil2cpp.so";

            if (diskLib != null) {
                setProgress(25, "Copying libil2cpp.so from disk...");
                log("ok", "Disk path: " + diskLib);
                copyFileRoot(diskLib, libPath);
                log("ok", "Disk copy done: " + getFileSizeMb(libPath) + " MB");
            } else {
                setProgress(25, "Memory dumping libil2cpp.so...");
                log("warn", "Disk path not found — falling back to memory dump");
                memDump(pid, libBase, libPath);
                log("ok", "Memory dump done: " + getFileSizeMb(libPath) + " MB");
            }

            setProgress(50, "Dumping global-metadata.dat...");
            String metaPath = outDir + "/global-metadata.dat";
            dumpMetadata(pid, metaPath);
            if (verifyMeta(metaPath)) log("ok", "Metadata magic OK");
            else { log("error", "Metadata magic FAILED"); return; }

            setProgress(60, "Running Il2CppDumper...");
            String dumpOut = outDir + "/Il2CppDumpOut";
            new File(dumpOut).mkdirs();
            boolean dumpOk = runIl2CppDumper(libPath, metaPath, dumpOut);

            if (!dumpOk) {
                log("error", "Il2CppDumper failed — check dotnet + dll setup");
                setProgress(100, "Failed");
                return;
            }

            setProgress(90, "Exporting offsets...");
            exportOffsets(dumpOut, outDir, libBase);

            setProgress(100, "Done ✓");
            lastOutDir = outDir;
            log("ok", "══════════════════════════════════");
            log("ok", " DUMP COMPLETE");
            log("ok", " Output: " + outDir);
            log("ok", " Base:   " + baseHex);
            log("ok", "══════════════════════════════════");

            ui(() -> btnExport.setEnabled(true));

        } catch (Exception e) {
            log("error", "Fatal: " + e.getMessage());
        }
    }

    // ── find disk lib ────────────────────────────────────────────────────────
    private String findDiskLib(String pkg) {
        String[] arches = {"arm64-v8a", "arm64"};
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c",
                            "find /data/app -path \"*" + pkg + "*/lib/*\" -name 'libil2cpp.so' 2>/dev/null | head -1"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                line = line.trim();
                // verify size > 50MB
                Process p2 = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "stat -c %s \"" + line + "\""});
                BufferedReader br2 = new BufferedReader(new InputStreamReader(p2.getInputStream()));
                String sz = br2.readLine();
                p2.waitFor();
                if (sz != null) {
                    long bytes = Long.parseLong(sz.trim());
                    if (bytes > 50L * 1024 * 1024) return line;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── get lib base from /proc/maps ─────────────────────────────────────────
    private long getLibBase(String pkg) {
        try {
            String pid = findPid(pkg);
            if (pid == null) return 0;
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c",
                            "grep 'libil2cpp.so' /proc/" + pid + "/maps | head -1"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = br.readLine();
            p.waitFor();
            if (line != null && !line.trim().isEmpty()) {
                String addr = line.trim().split("-")[0];
                return Long.parseUnsignedLong(addr, 16);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── copy file via root ───────────────────────────────────────────────────
    private void copyFileRoot(String src, String dst) throws Exception {
        runRootCmd("cp \"" + src + "\" \"" + dst + "\"");
        runRootCmd("chmod 644 \"" + dst + "\"");
    }

    // ── memory dump via wolfdumper binary ────────────────────────────────────
    private void memDump(String pid, long base, String outPath) throws Exception {
        // echo "2" to wolfdumper for dump-only mode, then pipe stdin
        // simpler: call wolfdumper via su with auto-input
        runRootCmd(BINARY_PATH + " --pid " + pid + " --lib-out \"" + outPath + "\" --dump-only");
    }

    // ── dump metadata ────────────────────────────────────────────────────────
    private void dumpMetadata(String pid, String outPath) throws Exception {
        runRootCmd(BINARY_PATH + " --pid " + pid + " --meta-out \"" + outPath + "\" --meta-only");
    }

    // ── run Il2CppDumper ─────────────────────────────────────────────────────
    private boolean runIl2CppDumper(String lib, String meta, String outDir) {
        if (!new File(DOTNET_PATH).exists() || !new File(IL2CPP_DLL).exists()) {
            log("warn", "dotnet or Il2CppDumper.dll not found — trying Python...");
            return runPythonDumper(lib, meta, outDir);
        }

        // write runtimeconfig if missing
        writeRuntimeConfigTo(IL2CPP_DLL.replace("Il2CppDumper.dll",
                "Il2CppDumper.runtimeconfig.json"));

        try {
            String cmd = DOTNET_PATH + " \"" + IL2CPP_DLL + "\" \"" + lib + "\" \"" + meta + "\" \"" + outDir + "\"";
            log("info", "dotnet CMD: " + cmd);

            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                final String l = line;
                if (l.contains("ERROR") || l.contains("Exception"))
                    log("error", l);
                else if (l.contains("OK") || l.contains("Completed") || l.contains("dump.cs"))
                    log("ok", l);
                else
                    log("info", l);
            }

            int rc = p.waitFor();
            log("info", "Il2CppDumper exit: " + rc);

            return new File(outDir + "/dump.cs").exists();
        } catch (Exception e) {
            log("error", "Il2CppDumper exception: " + e.getMessage());
            return false;
        }
    }

    private boolean runPythonDumper(String lib, String meta, String outDir) {
        String py = "/data/data/com.termux/files/usr/bin/python3";
        String script = "/data/local/tmp/il2cpp_dump.py";
        if (!new File(py).exists() || !new File(script).exists()) {
            log("error", "Python dumper not found either.");
            return false;
        }
        try {
            String cmd = py + " " + script + " \"" + lib + "\" \"" + meta + "\" \"" + outDir + "\"";
            log("info", "Python CMD: " + cmd);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) log("info", line);
            p.waitFor();
            return new File(outDir + "/dump.cs").exists();
        } catch (Exception e) {
            log("error", e.getMessage());
            return false;
        }
    }

    // ── auto-download Il2CppDumper ───────────────────────────────────────────
    // Downloads latest OxcyShop / Perfare release zip, extracts the needed dlls
    // into /data/local/tmp/Il2CppDumper/ via su
    private boolean downloadIl2CppDumper() {
        // Il2CppDumper latest release — net8.0 build (works with dotnet 10 too)
        // Using the well-known Perfare release which OxcyShop is based on
        String[] urls = {
            // Try OxcyShop fork artifacts first, then Perfare official
            "https://github.com/Perfare/Il2CppDumper/releases/latest/download/Il2CppDumper-net8.zip",
            "https://github.com/Perfare/Il2CppDumper/releases/download/v6.7.46/Il2CppDumper-net8.zip",
        };

        String tmpZip = "/data/local/tmp/Il2CppDumper_download.zip";
        String tmpDir = "/data/local/tmp/Il2CppDumper_extract";
        String outDir = "/data/local/tmp/Il2CppDumper";

        for (String url : urls) {
            log("info", "Trying: " + url);
            try {
                // download via curl (available in Termux/Android)
                String dlCmd = "curl -L --connect-timeout 30 --max-time 120 -o \"" + tmpZip + "\" \"" + url + "\" 2>&1";
                log("info", "Downloading Il2CppDumper...");

                ProcessBuilder pb = new ProcessBuilder("su", "-c", dlCmd);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = br.readLine()) != null) {
                    // curl progress lines — only show % lines
                    if (line.contains("%") || line.contains("error") || line.contains("Error"))
                        log("info", "  " + line.trim());
                }
                int rc = p.waitFor();

                if (rc != 0) {
                    log("warn", "curl failed (exit " + rc + "), trying next URL...");
                    continue;
                }

                // check downloaded file size > 100KB
                Process ps = Runtime.getRuntime().exec(
                        new String[]{"su", "-c", "stat -c %s \"" + tmpZip + "\""});
                BufferedReader brs = new BufferedReader(new InputStreamReader(ps.getInputStream()));
                String szLine = brs.readLine();
                ps.waitFor();
                if (szLine == null || Long.parseLong(szLine.trim()) < 100_000) {
                    log("warn", "Downloaded file too small — bad URL, trying next...");
                    continue;
                }
                log("ok", "Download complete: " + String.format(Locale.US, "%.1f",
                        Long.parseLong(szLine.trim()) / (1024.0 * 1024.0)) + " MB");

                // extract
                log("info", "Extracting...");
                runRootCmd("mkdir -p \"" + tmpDir + "\"");
                runRootCmd("unzip -o \"" + tmpZip + "\" -d \"" + tmpDir + "\"");

                // find and copy dlls
                runRootCmd("mkdir -p \"" + outDir + "\"");

                // copy all .dll files from extracted dir
                runRootCmd("find \"" + tmpDir + "\" -name '*.dll' -exec cp {} \"" + outDir + "/\" \\;");
                // copy config.json
                runRootCmd("find \"" + tmpDir + "\" -name 'config.json' | head -1 | xargs -I{} cp {} \"" + outDir + "/\"");

                // cleanup
                runRootCmd("rm -f \"" + tmpZip + "\"");
                runRootCmd("rm -rf \"" + tmpDir + "\"");

                // write runtimeconfig for net10 compatibility
                writeRuntimeConfigTo(outDir + "/Il2CppDumper.runtimeconfig.json");

                // verify
                boolean ok = new File(IL2CPP_DLL).exists();
                if (ok) {
                    log("ok", "Il2CppDumper.dll installed at " + outDir);
                    // list files
                    try {
                        Process pl = Runtime.getRuntime().exec(
                                new String[]{"su", "-c", "ls \"" + outDir + "\""});
                        BufferedReader brl = new BufferedReader(new InputStreamReader(pl.getInputStream()));
                        StringBuilder files = new StringBuilder("Files: ");
                        String fl;
                        while ((fl = brl.readLine()) != null) files.append(fl).append(" | ");
                        pl.waitFor();
                        log("info", files.toString());
                    } catch (Exception ignored) {}
                    return true;
                } else {
                    log("warn", "Il2CppDumper.dll not found after extraction");
                }
            } catch (Exception e) {
                log("warn", "Download error: " + e.getMessage());
            }
        }
        return false;
    }

    // ── write runtimeconfig ──────────────────────────────────────────────────
    private void writeRuntimeConfig() {
        writeRuntimeConfigTo("/data/local/tmp/Il2CppDumper/Il2CppDumper.runtimeconfig.json");
    }

    private void writeRuntimeConfigTo(String path) {
        try {
            String json = "{"
                + "\"runtimeOptions\":{"
                + "\"tfm\":\"net10.0\","
                + "\"framework\":{"
                + "\"name\":\"Microsoft.NETCore.App\","
                + "\"version\":\"10.0.0\""
                + "}}}";
            // use printf to avoid shell quoting issues with echo
            runRootCmd("printf '%s' '" + json + "' > \"" + path + "\"");
            log("info", "Wrote runtimeconfig.json → " + path);
        } catch (Exception e) {
            log("warn", "runtimeconfig write failed: " + e.getMessage());
        }
    }

    // ── export offsets (reads dump.cs, writes offsets.txt + initBase) ────────
    private void exportOffsets(String dumpDir, String outDir, long libBase) {
        File dumpCs = new File(dumpDir + "/dump.cs");
        if (!dumpCs.exists()) { log("warn", "dump.cs not found, skipping offset export"); return; }

        try {
            StringBuilder offsetsTxt = new StringBuilder();
            StringBuilder offsetsJson = new StringBuilder("[\n");

            offsetsTxt.append("// WolfDumper v3.0 — Auto-generated offsets\n");
            offsetsTxt.append(String.format("// InitBase: 0x%X\n\n", libBase));
            offsetsJson.append(String.format("  {\"initBase\": \"0x%X\"},\n", libBase));

            // parse dump.cs for method offsets
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(dumpCs)));
            String cls = "";
            String line;
            int count = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("// Namespace:") || line.startsWith("public class") || line.startsWith("public sealed class")) {
                    int ci = line.indexOf("class ");
                    if (ci >= 0) {
                        String[] parts = line.substring(ci + 6).split("[\\s:{]");
                        if (parts.length > 0) cls = parts[0].trim();
                    }
                }
                if (line.startsWith("// RVA:") && line.contains("Offset:")) {
                    // // RVA: 0x... Offset: 0x... VA: 0x...
                    String[] parts = line.split("\\s+");
                    String offset = "";
                    for (int i = 0; i < parts.length - 1; i++) {
                        if (parts[i].equals("Offset:")) { offset = parts[i + 1]; break; }
                    }
                    // next line is the method sig
                    String sig = br.readLine();
                    if (sig != null && !offset.isEmpty()) {
                        sig = sig.trim();
                        offsetsTxt.append(cls).append(" :: ").append(sig).append(" → ").append(offset).append("\n");
                        // JSON
                        String safeSig = sig.replace("\"", "\\\"");
                        offsetsJson.append(String.format(
                                "  {\"class\": \"%s\", \"member\": \"%s\", \"offset\": \"%s\"},\n",
                                cls, safeSig, offset));
                        count++;
                    }
                }
            }
            br.close();

            // trim last comma
            if (offsetsJson.length() > 2) {
                int last = offsetsJson.lastIndexOf(",");
                if (last >= 0) offsetsJson.deleteCharAt(last);
            }
            offsetsJson.append("]\n");

            // write files
            writeText(outDir + "/offsets.txt", offsetsTxt.toString());
            writeText(outDir + "/offsets.json", offsetsJson.toString());

            // initBase.txt — just the base address, easy to copy
            writeText(outDir + "/initBase.txt",
                    String.format("InitBase = 0x%X\nLibBase  = 0x%X\n", libBase, libBase));

            log("ok", "Exported " + count + " offsets");
            log("ok", "initBase.txt written: 0x" + Long.toHexString(libBase).toUpperCase());

        } catch (Exception e) {
            log("error", "Offset export failed: " + e.getMessage());
        }
    }

    // ── export / share output folder ─────────────────────────────────────────
    private void exportResult(String dir) {
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setMessage("Output dir:\n" + dir + "\n\nOpen in file manager?")
                .setPositiveButton("Open", (d, w) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        intent.setDataAndType(Uri.parse("file://" + dir), "resource/folder");
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "Open " + dir + " manually", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private void runRootCmd(String cmd) throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
        BufferedReader stderr = new BufferedReader(new InputStreamReader(p.getErrorStream()));
        String line;
        while ((line = stderr.readLine()) != null) log("warn", line);
        p.waitFor();
    }

    private void writeText(String path, String content) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    private boolean verifyMeta(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] magic = new byte[4];
            fis.read(magic);
            return (magic[0] & 0xFF) == 0xAF && (magic[1] & 0xFF) == 0x1B
                    && (magic[2] & 0xFF) == 0xB1 && (magic[3] & 0xFF) == 0xFA;
        } catch (Exception e) { return false; }
    }

    private String getFileSizeMb(String path) {
        File f = new File(path);
        return String.format(Locale.US, "%.2f", f.length() / (1024.0 * 1024.0));
    }

    // ── logging ──────────────────────────────────────────────────────────────
    private static final int COL_OK    = Color.parseColor("#00FF88");
    private static final int COL_WARN  = Color.parseColor("#FFD600");
    private static final int COL_ERROR = Color.parseColor("#FF3B3B");
    private static final int COL_INFO  = Color.parseColor("#CCCCCC");

    private void log(String level, String msg) {
        int color = switch (level) {
            case "ok"    -> COL_OK;
            case "warn"  -> COL_WARN;
            case "error" -> COL_ERROR;
            default      -> COL_INFO;
        };
        String prefix = switch (level) {
            case "ok"    -> "[+] ";
            case "warn"  -> "[!] ";
            case "error" -> "[✗] ";
            default      -> "[*] ";
        };
        String line = prefix + msg + "\n";
        SpannableString span = new SpannableString(line);
        span.setSpan(new ForegroundColorSpan(color), 0, line.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        ui(() -> {
            tvConsole.append(span);
            scrollConsole.post(() -> scrollConsole.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void setProgress(int pct, String stage) {
        ui(() -> {
            progressBar.setProgress(pct);
            tvStage.setText(stage);
            tvPct.setText(pct + "%");
        });
    }

    private void ui(Runnable r) {
        handler.post(r);
    }

    // ── permissions ──────────────────────────────────────────────────────────
    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
            }
        }
    }
}
