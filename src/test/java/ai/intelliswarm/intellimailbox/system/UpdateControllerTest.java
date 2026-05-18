package ai.intelliswarm.intellimailbox.system;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the upgrade-flow REST surface (issue #3). Mocks
 * {@link UpdateChecker} and {@link UpdateInstaller} so nothing actually
 * hits GitHub or spawns {@code msiexec} — we're only validating that the
 * controller maps states to HTTP correctly and emits the JSON shape the
 * Angular banner relies on.
 */
@WebMvcTest(UpdateController.class)
@TestPropertySource(properties = "intellimailbox.launcher.enabled=false")
class UpdateControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private UpdateChecker checker;
    @MockitoBean private UpdateInstaller installer;

    @Test
    void statusReturnsIdleSnapshotOnFreshApp() throws Exception {
        when(installer.snapshot()).thenReturn(new UpdateInstaller.Snapshot(
                UpdateInstaller.State.IDLE, 0L, 0L, null, null));

        mvc.perform(get("/api/update/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.bytesRead").value(0))
                .andExpect(jsonPath("$.totalBytes").value(0))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void downloadIsConflictWhenNoUpdateIsAvailable() throws Exception {
        when(checker.snapshot()).thenReturn(new UpdateChecker.VersionInfo(
                "0.1.5", "0.1.5", false, null, null, null, 0L));

        mvc.perform(post("/api/update/download"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "No update is currently available."));

        verify(installer, never()).startDownload(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void downloadKicksOffInstallerAndReturnsAccepted() throws Exception {
        when(checker.snapshot()).thenReturn(new UpdateChecker.VersionInfo(
                "0.1.5", "0.1.6", true,
                "https://example/release",
                "https://example/asset.msi", "asset.msi", 12345L));
        when(installer.startDownload(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(true);
        when(installer.snapshot()).thenReturn(new UpdateInstaller.Snapshot(
                UpdateInstaller.State.DOWNLOADING, 0L, 12345L, "0.1.6", null));

        mvc.perform(post("/api/update/download"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("DOWNLOADING"))
                .andExpect(jsonPath("$.totalBytes").value(12345));

        verify(installer, times(1)).startDownload(
                "https://example/asset.msi", "asset.msi", 12345L, "0.1.6");
    }

    @Test
    void installIsConflictBeforeDownloadCompletes() throws Exception {
        // Mirror the real wiring: installer.startInstall() returns false in any
        // non-READY state — IDLE here represents "user pressed Install without
        // first downloading", which the UI should never do but the API must
        // defend against anyway.
        when(installer.startInstall()).thenReturn(false);
        when(installer.snapshot()).thenReturn(new UpdateInstaller.Snapshot(
                UpdateInstaller.State.IDLE, 0L, 0L, null,
                "Downloaded installer is missing on disk."));

        mvc.perform(post("/api/update/install"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.state").value("IDLE"))
                .andExpect(jsonPath("$.error").value(
                        "Downloaded installer is missing on disk."));
    }

    @Test
    void installReturnsAcceptedWhenInstallerLaunches() throws Exception {
        when(installer.startInstall()).thenReturn(true);
        when(installer.snapshot()).thenReturn(new UpdateInstaller.Snapshot(
                UpdateInstaller.State.INSTALLING, 12345L, 12345L, "0.1.6", null));

        mvc.perform(post("/api/update/install"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("INSTALLING"))
                .andExpect(jsonPath("$.targetVersion").value("0.1.6"));
    }
}
