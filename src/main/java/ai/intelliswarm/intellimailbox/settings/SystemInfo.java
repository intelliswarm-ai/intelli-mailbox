package ai.intelliswarm.intellimailbox.settings;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Probes the host for RAM / CPU / OS and turns that into a recommended
 * Ollama model list. The bands are deliberately conservative — Ollama keeps
 * the model resident in RAM during generation, so leaving headroom for the
 * OS + Chrome + the JVM matters more than squeezing in the largest model.
 */
public final class SystemInfo {

    private SystemInfo() { }

    public static Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long ramBytes = totalRamBytes();
        double ramGb = ramBytes / (1024.0 * 1024.0 * 1024.0);
        int cpus = Runtime.getRuntime().availableProcessors();
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");

        out.put("os", os);
        out.put("osFamily", osFamily(os));
        out.put("arch", arch);
        out.put("cpuCores", cpus);
        out.put("ramBytes", ramBytes);
        out.put("ramGb", round1(ramGb));
        out.put("recommendedModels", recommend(ramGb));
        out.put("installInstructions", installInstructions(os));
        return out;
    }

    private static long totalRamBytes() {
        try {
            var bean = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
            return bean.getTotalMemorySize();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static String osFamily(String os) {
        String l = os.toLowerCase();
        if (l.contains("win")) return "windows";
        if (l.contains("mac") || l.contains("darwin")) return "mac";
        return "linux";
    }

    /**
     * Each entry: { model, sizeGb, ramGbMin, note, recommended }. The first
     * {@code recommended:true} entry is what the UI highlights as the default.
     */
    static List<Map<String, Object>> recommend(double ramGb) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(model("qwen2.5:1.5b", 1.0, 4,
                "Tiny but solid — good fallback on RAM-constrained laptops."));
        all.add(model("qwen2.5:3b", 2.0, 6,
                "Default. Fast on CPU, strong on the structured-JSON output the enricher needs."));
        all.add(model("llama3.2:3b", 2.0, 6,
                "Similar size to qwen2.5:3b, slightly looser JSON adherence."));
        all.add(model("phi3:mini", 2.3, 6,
                "Microsoft Phi-3 mini, 3.8B params — punches above its weight."));
        all.add(model("qwen2.5:7b", 4.4, 12,
                "Sharper reasoning, still CPU-viable on 16 GB+ machines."));
        all.add(model("llama3.1:8b", 4.7, 16,
                "Meta Llama 3.1 8B — well-rounded, needs 16 GB+ comfortably."));
        all.add(model("mistral:7b", 4.1, 12,
                "Mistral 7B — clean instruction-following."));
        all.add(model("qwen2.5:14b", 9.0, 24,
                "Sharper still — best on 32 GB+ with a discrete GPU or Apple Silicon."));

        // Pick the highest-quality model that fits in this machine's RAM band,
        // with a small bias toward the well-tested default.
        String defaultModel;
        if (ramGb < 6)        defaultModel = "qwen2.5:1.5b";
        else if (ramGb < 12)  defaultModel = "qwen2.5:3b";
        else if (ramGb < 24)  defaultModel = "qwen2.5:7b";
        else                  defaultModel = "llama3.1:8b";

        for (Map<String, Object> m : all) {
            int min = (int) m.get("ramGbMin");
            m.put("fits", ramGb <= 0 || ramGb >= min);
            m.put("recommended", m.get("model").equals(defaultModel));
        }
        return all;
    }

    private static Map<String, Object> model(String name, double sizeGb, int ramGbMin, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", name);
        m.put("sizeGb", sizeGb);
        m.put("ramGbMin", ramGbMin);
        m.put("note", note);
        m.put("pullCommand", "ollama pull " + name);
        return m;
    }

    static Map<String, Object> installInstructions(String os) {
        String fam = osFamily(os);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("osFamily", fam);
        switch (fam) {
            case "mac" -> {
                out.put("primary", "brew install ollama && brew services start ollama");
                out.put("alternative", "Download the .dmg from https://ollama.com/download");
                out.put("verify", "ollama --version");
                out.put("startServer", "ollama serve   # runs on http://localhost:11434");
            }
            case "windows" -> {
                out.put("primary", "Download and run the installer from https://ollama.com/download");
                out.put("alternative", "winget install Ollama.Ollama");
                out.put("verify", "ollama --version");
                out.put("startServer", "Ollama runs as a background service after install — no extra step needed.");
            }
            default -> {
                out.put("primary", "curl -fsSL https://ollama.com/install.sh | sh");
                out.put("alternative", "See https://github.com/ollama/ollama/blob/main/docs/linux.md for manual / docker setups");
                out.put("verify", "ollama --version");
                out.put("startServer", "sudo systemctl start ollama   # listens on http://localhost:11434");
            }
        }
        return out;
    }

    private static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
