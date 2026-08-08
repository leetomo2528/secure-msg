package com.yunjelee.securemsg.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunjelee.securemsg.UpdateInfo
import com.yunjelee.securemsg.BuildConfig
import java.io.File

/** Game-style in-app update flow states driven by [MainActivity]. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val pct: Int) : UpdateUiState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateUiState
    data class Installing(val info: UpdateInfo, val file: File) : UpdateUiState
    data class SessionSubmitted(val info: UpdateInfo, val file: File) : UpdateUiState
    data class NeedsPermission(val info: UpdateInfo, val file: File) : UpdateUiState
    data class InstallBlocked(val info: UpdateInfo, val file: File, val detail: String) :
        UpdateUiState
    data class Failed(val message: String, val info: UpdateInfo?) : UpdateUiState
}

/**
 * Snapshot of the in-app update flow passed down from MainActivity so
 * MainScreen/SettingsPane don't need 11 individual parameters.
 */
data class UpdateFlow(
    val state: UpdateUiState,
    val message: String?,
    val autoEnabled: Boolean,
    val shouldAutoCheck: Boolean,
    val onCheck: (manual: Boolean) -> Unit,
    val onToggleAuto: (Boolean) -> Unit,
    val onUpdate: (UpdateInfo) -> Unit,
    val onInstall: (UpdateInfo, File) -> Unit,
    val onRetry: (UpdateInfo?) -> Unit,
    val onCloseInstallBlocked: () -> Unit,
    val onDismiss: (UpdateInfo) -> Unit,
)

/** Inline update banner shown above the tabs; renders every state but Idle. */
@Composable
fun UpdateBanner(
    state: UpdateUiState,
    onUpdate: (UpdateInfo) -> Unit,
    onInstall: (UpdateInfo, File) -> Unit,
    onRetry: (UpdateInfo?) -> Unit,
    onCloseInstallBlocked: () -> Unit,
    onDismiss: (UpdateInfo) -> Unit,
) {
    when (state) {
        is UpdateUiState.Available -> Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sm.gradientSoft)
                .border(1.dp, Sm.teal.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "새 버전 v${state.info.versionName} 출시 (현재 v${BuildConfig.VERSION_NAME})",
                color = Sm.text1,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.weight(1f),
            )
            SmGradientButton(text = "업데이트", onClick = { onUpdate(state.info) })
            TextButton(onClick = { onDismiss(state.info) }) {
                Text("나중에", color = Sm.text3, fontSize = 12.sp)
            }
        }
        is UpdateUiState.Downloading -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sm.surface)
                .border(1.dp, Sm.border, RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("업데이트 다운로드 중… ${state.pct}%", color = Sm.cyan, fontSize = 12.sp)
            LinearProgressIndicator(
                progress = { state.pct / 100f },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                color = Sm.teal,
                trackColor = Sm.surfaceAlt,
            )
        }
        is UpdateUiState.Ready -> SmGradientButton(
            text = "v${state.info.versionName} 지금 설치",
            onClick = { onInstall(state.info, state.file) },
            modifier = Modifier.fillMaxWidth(),
        )
        is UpdateUiState.Installing -> InstallProgressBanner(
            "설치 파일을 시스템 설치 프로그램으로 전달하는 중…",
        )
        is UpdateUiState.SessionSubmitted -> InstallProgressBanner(
            "시스템 설치 확인을 기다리는 중…",
        )
        is UpdateUiState.NeedsPermission -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sm.warning.copy(alpha = 0.07f))
                .border(1.dp, Sm.warning.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            Text(
                "설치 권한이 필요합니다. 방금 열린 설정에서 '이 앱의 설치 허용'을 켜 주세요. 허용하면 자동으로 설치가 이어집니다.",
                color = Sm.warning,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            TextButton(onClick = { onInstall(state.info, state.file) }) {
                Text("다시 시도", color = Sm.text2)
            }
        }
        is UpdateUiState.InstallBlocked -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Sm.danger.copy(alpha = 0.06f))
                .border(1.dp, Sm.danger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "업데이트 설치 차단됨 (v${state.info.versionName})",
                color = Sm.danger,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                state.detail,
                color = Sm.text2,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmGradientButton(
                    text = "재시도",
                    onClick = { onInstall(state.info, state.file) },
                    modifier = Modifier.weight(1f),
                )
                SmGhostButton(
                    text = "닫기",
                    onClick = onCloseInstallBlocked,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is UpdateUiState.Failed -> if (state.info != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Sm.danger.copy(alpha = 0.06f))
                    .border(1.dp, Sm.danger.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.message,
                    color = Sm.danger,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRetry(state.info) }) {
                    Text("재시도", color = Sm.text2)
                }
            }
        }
        else -> {}
    }
}

@Composable
private fun InstallProgressBanner(message: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Sm.surface)
            .border(1.dp, Sm.border, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(message, color = Sm.cyan, fontSize = 12.sp)
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
            color = Sm.teal,
            trackColor = Sm.surfaceAlt,
        )
    }
}
