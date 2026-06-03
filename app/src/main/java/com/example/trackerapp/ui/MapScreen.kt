package com.example.trackerapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.trackerapp.TrackingConfig
import com.example.trackerapp.db.TrackDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit
) {
    var isTracking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val db = remember { TrackDatabase.getDatabase(context) }
    
    val pointsFlow = remember {
        db.trackDao().getLatestTrack().flatMapLatest { track ->
            if (track != null) db.trackDao().getPointsForTrack(track.id)
            else emptyFlow()
        }
    }
    val points by pointsFlow.collectAsState(initial = emptyList())
    
    val noise by TrackingConfig.noiseThreshold.collectAsState()
    val decay by TrackingConfig.velocityDecay.collectAsState()
    val scale by TrackingConfig.distanceScale.collectAsState()

    val sensorPoints = points.filter { it.source == "SENSOR" }
    var showArea by remember { mutableStateOf(false) }
    var showCalibrationDialog by remember { mutableStateOf(false) }
    
    /* 
    // 保留 GPS 資料讀取 (目前以註解方式關閉)
    val gpsPoints = points.filter { it.source == "GPS" }
    */

    var totalDistance = 0f
    for (i in 1 until sensorPoints.size) {
        val dx = sensorPoints[i].x - sensorPoints[i-1].x
        val dy = sensorPoints[i].y - sensorPoints[i-1].y
        totalDistance += sqrt(dx*dx + dy*dy)
    }

    var closureErrorLength = 0f
    var closureErrorAngle = 0f
    var finalX = 0f
    var finalY = 0f
    var enclosedArea = 0.0

    if (sensorPoints.isNotEmpty()) {
        val lastPoint = sensorPoints.last()
        finalX = lastPoint.x
        finalY = lastPoint.y
        closureErrorLength = sqrt(finalX*finalX + finalY*finalY)
        var angle = Math.toDegrees(atan2(finalY.toDouble(), finalX.toDouble())).toFloat()
        if (angle < 0) angle += 360f
        closureErrorAngle = angle

        // 鞋帶公式 (Shoelace Formula) 計算多邊形面積
        if (showArea && sensorPoints.size >= 3) {
            var sum = 0.0
            for (i in 0 until sensorPoints.size - 1) {
                val current = sensorPoints[i]
                val next = sensorPoints[i+1]
                sum += (current.x.toDouble() * next.y.toDouble()) - (next.x.toDouble() * current.y.toDouble())
            }
            // 閉合最後一點與第一點
            val first = sensorPoints.first()
            val last = sensorPoints.last()
            sum += (last.x.toDouble() * first.y.toDouble()) - (first.x.toDouble() * last.y.toDouble())
            
            enclosedArea = Math.abs(sum) / 2.0
        }
    }

    // 簡約風格背景色
    val backgroundColor = Color(0xFFF8F9FA) 
    val cardColor = Color.White

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "軌跡分析系統", 
            fontSize = 24.sp, 
            fontWeight = FontWeight.Bold, 
            color = Color(0xFF2D3748), 
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 參數設定卡片
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp), 
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("參數校準", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = Color(0xFF4A5568))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("轉彎穩定", fontSize = 13.sp, color = Color.Gray)
                        Switch(
                            checked = TrackingConfig.turnStabilizer.collectAsState().value,
                            onCheckedChange = { TrackingConfig.turnStabilizer.value = it },
                            modifier = Modifier.scale(0.7f).padding(horizontal = 4.dp)
                        )
                        TextButton(onClick = { showCalibrationDialog = true }, contentPadding = PaddingValues(0.dp)) {
                            Text("距離校準", fontSize = 14.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("計步門檻: %.1f", noise), modifier = Modifier.weight(1.2f), fontSize = 13.sp, color = Color.Gray)
                    Slider(value = noise, onValueChange = { TrackingConfig.noiseThreshold.value = it }, valueRange = 0f..15f, modifier = Modifier.weight(2f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(String.format("步長(m): %.2f", scale), modifier = Modifier.weight(1.2f), fontSize = 13.sp, color = Color.Gray)
                    Slider(value = scale, onValueChange = { TrackingConfig.distanceScale.value = it }, valueRange = 0f..2f, modifier = Modifier.weight(2f))
                }
            }
        }

        // 軌跡畫布卡片
        Card(
            modifier = Modifier.fillMaxWidth().height(260.dp).padding(bottom = 20.dp), 
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val allPoints = sensorPoints
                if (allPoints.isEmpty()) return@Canvas

                val minX = minOf(allPoints.minOfOrNull { it.x } ?: 0f, 0f)
                val maxX = maxOf(allPoints.maxOfOrNull { it.x } ?: 0f, 0f)
                val minY = minOf(allPoints.minOfOrNull { it.y } ?: 0f, 0f)
                val maxY = maxOf(allPoints.maxOfOrNull { it.y } ?: 0f, 0f)

                val boundsWidth = max((maxX - minX), 1f) + 2f
                val boundsHeight = max((maxY - minY), 1f) + 2f
                val s = minOf(canvasWidth / boundsWidth, canvasHeight / boundsHeight)

                val centerX = (maxX + minX) / 2f
                val centerY = (maxY + minY) / 2f

                fun mapToCanvas(x: Float, y: Float): Offset {
                    val dx = x - centerX
                    val dy = y - centerY
                    return Offset(canvasWidth / 2f + dx * s, canvasHeight / 2f - dy * s)
                }

                // 十字坐標軸
                val origin = mapToCanvas(0f, 0f)
                drawLine(color = Color(0xFFE2E8F0), start = Offset(0f, origin.y), end = Offset(canvasWidth, origin.y), strokeWidth = 1.5f)
                drawLine(color = Color(0xFFE2E8F0), start = Offset(origin.x, 0f), end = Offset(origin.x, canvasHeight), strokeWidth = 1.5f)

                /*
                // 保留 GPS 繪製邏輯 (註解中)
                if (gpsPoints.isNotEmpty()) {
                    val gpsPath = Path().apply {
                        val first = mapToCanvas(gpsPoints.first().x, gpsPoints.first().y)
                        moveTo(first.x, first.y)
                        for (i in 1 until gpsPoints.size) {
                            val pt = mapToCanvas(gpsPoints[i].x, gpsPoints[i].y)
                            lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(path = gpsPath, color = Color(0xFFCBD5E0), style = Stroke(width = 3f))
                }
                */

                // 實際軌跡
                if (sensorPoints.isNotEmpty()) {
                    val sensorPath = Path().apply {
                        val first = mapToCanvas(sensorPoints.first().x, sensorPoints.first().y)
                        moveTo(first.x, first.y)
                        for (i in 1 until sensorPoints.size) {
                            val pt = mapToCanvas(sensorPoints[i].x, sensorPoints[i].y)
                            lineTo(pt.x, pt.y)
                        }
                    }
                    drawPath(path = sensorPath, color = Color(0xFF5A67D8), style = Stroke(width = 5f))
                    
                    val currentPoint = mapToCanvas(sensorPoints.last().x, sensorPoints.last().y)
                    drawCircle(color = Color(0xFFF56565), radius = 12f, center = currentPoint)
                }
            }
        }

        // 數據面板 (2x2 網格)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("總距離", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.4f m", totalDistance), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("封閉面積", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.4f m²", enclosedArea), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2D3748))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("長度誤差", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.4f m", closureErrorLength), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53E3E))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("角度誤差", fontSize = 12.sp, color = Color.Gray)
                    Text(String.format("%.4f°", closureErrorAngle), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53E3E))
                }
            }
        }

        // 操作按鈕區
        Column(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (isTracking) {
                        isTracking = false
                        onStopTracking()
                    } else {
                        isTracking = true
                        showArea = false // 重開追蹤時先不顯示面積
                        onStartTracking()
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if(isTracking) Color(0xFFE53E3E) else Color(0xFF5A67D8)),
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp)
            ) {
                Text(if (isTracking) "停止紀錄" else "開始記錄", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { showArea = true },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF48BB78)), // 綠色
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("計算面積", color = Color.White, fontWeight = FontWeight.Bold)
                }
                
                Button(
                    onClick = {
                        showArea = false
                        val intent = android.content.Intent(context, com.example.trackerapp.TrackingService::class.java).apply {
                            action = com.example.trackerapp.TrackingService.ACTION_RESET_TRACKING
                        }
                        context.startService(intent)
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA0AEC0)), // 灰色
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("重新歸零", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // 自動校準彈出視窗
    if (showCalibrationDialog) {
        var inputDistance by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCalibrationDialog = false },
            title = { Text("自動校準距離比例", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("請輸入您剛剛『實際走過』的直線總距離 (公尺)：", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = inputDistance,
                        onValueChange = { inputDistance = it },
                        singleLine = true,
                        placeholder = { Text("例如：5.0") }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("系統測量總距離：${String.format("%.4f", totalDistance)} m", color = Color.Gray, fontSize = 12.sp)
                    Text("⚠️ 建議走一段直線再校準。校準完成後請按「重新歸零」再開始測量新形狀。", color = Color(0xFFE53E3E), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val actual = inputDistance.toFloatOrNull()
                        if (actual != null && actual > 0f && totalDistance > 0f) {
                            val currentScale = TrackingConfig.distanceScale.value
                            val newScale = currentScale * (actual / totalDistance)
                            TrackingConfig.distanceScale.value = newScale
                        }
                        showCalibrationDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A67D8))
                ) {
                    Text("校準")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCalibrationDialog = false }) {
                    Text("取消", color = Color.Gray)
                }
            }
        )
    }
}
