package com.codereview.agent.core.autofix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱可用性探测器（对齐 dsh {@code LocalSandboxProvider} 的 runner 探测）。
 *
 * <p>在 apply 前探测宿主是否具备隔离执行能力，探测到任一可用即视为可用：
 * <ul>
 *   <li>Linux：{@code bwrap}（bubblewrap）或 {@code firejail}；</li>
 *   <li>macOS：{@code sandbox-exec}（Seatbelt）；</li>
 *   <li>通用兜底：{@code docker}（可做隔离容器）。</li>
 * </ul>
 *
 * <p><b>fail-closed 不变量</b>：探测失败 / 全部命令缺失一律返回 {@link Status#UNAVAILABLE}，
 * 绝不静默放行 —— 与 {@link AutoFixSafetyPolicy} 联动，沙箱不可用时 APPLY 被拒。
 */
@Component
public class SandboxProbe {

    private static final Logger log = LoggerFactory.getLogger(SandboxProbe.class);

    /** 探测候选命令（任一存在即视为沙箱可用）。 */
    private static final List<String> RUNNERS = List.of(
            "bwrap",        // Linux bubblewrap
            "firejail",     // Linux firejail
            "sandbox-exec", // macOS Seatbelt
            "docker"        // 通用容器兜底
    );

    /** 探测结果。 */
    public record Status(boolean available, List<String> foundRunners, String detail) {
        static Status unavailable(String detail) {
            return new Status(false, List.of(), detail);
        }
    }

    /**
     * 探测宿主沙箱能力。
     *
     * @return 探测结果（探测异常按不可用处理，fail-closed）
     */
    public Status detect() {
        List<String> found = new ArrayList<>();
        for (String runner : RUNNERS) {
            if (commandExists(runner)) {
                found.add(runner);
            }
        }
        if (!found.isEmpty()) {
            return new Status(true, found, "探测到沙箱 runner：" + String.join(", ", found));
        }
        return Status.unavailable("未探测到任何沙箱 runner（bwrap/firejail/sandbox-exec/docker）");
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + cmd)
                    .redirectErrorStream(true)
                    .start();
            int exit = p.waitFor();
            if (exit != 0) {
                return false;
            }
            // 进一步确认输出非空（command -v 命中会打印路径）
            byte[] out = p.getInputStream().readAllBytes();
            return !new String(out).isBlank();
        } catch (Exception e) {
            log.debug("[Sandbox] 探测 {} 失败（视为不可用）：{}", cmd, e.getMessage());
            return false;
        }
    }
}
