package cc.bestly.dsh

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * P1.0 —— 构建链验证页。
 *
 * 这一版故意只做一件事：证明「GitHub Actions 能构建出能装能跑的 APK」。
 * 不碰 dsh、不碰 payload、不 fork 任何进程，失败了也伤不到现有部署。
 *
 * 下一版（P1.1）才接引擎启动链。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HelloScreen()
                }
            }
        }
    }
}

@Composable
fun HelloScreen() {
    var taps by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("dsh android host", fontSize = 26.sp)
        Spacer(Modifier.height(6.dp))
        Text("P1.0 · 构建链验证", fontSize = 14.sp)
        Spacer(Modifier.height(28.dp))

        InfoLine("包名", BuildConfig.APPLICATION_ID)
        InfoLine("版本", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
        InfoLine("Android SDK", Build.VERSION.SDK_INT.toString())
        InfoLine("ABI", Build.SUPPORTED_ABIS.joinToString(", "))
        InfoLine("机型", Build.MANUFACTURER + " " + Build.MODEL)

        Spacer(Modifier.height(28.dp))
        Button(onClick = { taps++ }) {
            Text(if (taps == 0) "点我验证交互" else "已点击 " + taps + " 次 ✓")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "看到这行字，说明构建链、签名、安装、运行四步全通",
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        text = label + ": " + value,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 2.dp)
    )
}
