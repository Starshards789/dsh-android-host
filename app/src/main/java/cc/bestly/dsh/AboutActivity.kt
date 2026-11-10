package cc.bestly.dsh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 关于 / 开源许可页。
 *
 * 存在的原因：本应用随包分发 4 个官方插件（accessibility / android /
 * shizuku / vscreen）与 dsh 内核，它们都是 MIT 许可。
 * MIT 要求「许可声明须随所有副本」——只写进仓库的 THIRD_PARTY_NOTICES.md
 * 不够，必须随 APK 一起分发并让用户能看到。
 *
 * 实现：许可文本放在 assets/licenses/*.txt，本页列出文件名，
 * 点开显示全文。不联网、不依赖 WebView。
 */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            ) {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val names = remember { ctx.assets.list("licenses")?.sorted() ?: emptyArray() }
    var sel by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("返回", fontSize = 11.sp) }
                Spacer(Modifier.width(8.dp))
                Text("开源许可", fontSize = 15.sp)
            }
            Spacer(Modifier.height(8.dp))

            val current = sel
            if (current == null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("本应用随包分发以下 MIT 许可的开源组件：", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    names.forEach { name ->
                        TextButton(onClick = { sel = name }) {
                            Text(name.removeSuffix(".txt"), fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "完整依赖清单见仓库 THIRD_PARTY_NOTICES.md。",
                        fontSize = 10.sp
                    )
                }
            } else {
                TextButton(onClick = { sel = null }) { Text("← 返回列表", fontSize = 11.sp) }
                Spacer(Modifier.height(4.dp))
                val body = remember(current) {
                    runCatching {
                        ctx.assets.open("licenses/$current").bufferedReader().use { it.readText() }
                    }.getOrDefault("(读取失败：$current)")
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(body, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
