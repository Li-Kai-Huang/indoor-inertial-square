package com.example.trackerapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.trackerapp.db.TrackDatabase
import com.example.trackerapp.db.TrackEntity
import com.example.trackerapp.db.TrackPointEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/* 
// --- GPS 保留區 (目前以註解關閉) ---
import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.*
*/

class TrackingService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    
    private var rotationSensor: Sensor? = null
    private var linearAccelSensor: Sensor? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTrackId: Long = -1

    // PDR Variables (Sensor Double Integration)
    private var baseAzimuthRad: Float? = null
    private var currentAzimuthRad: Float = 0f
    private var slowAzimuthX: Float = 0f
    private var slowAzimuthY: Float = 1f
    private var lastTimestampNS: Long = 0
    private var velocityX: Float = 0f
    private var velocityY: Float = 0f
    private var currentX: Float = 0f
    private var currentY: Float = 0f

    /*
    // --- GPS 保留區 ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var startGpsLat: Double? = null
    private var startGpsLon: Double? = null
    */

    companion object {
        const val ACTION_START_TRACKING = "ACTION_START_TRACKING"
        const val ACTION_STOP_TRACKING = "ACTION_STOP_TRACKING"
        const val ACTION_RESET_TRACKING = "ACTION_RESET_TRACKING"
        private const val NOTIFICATION_CHANNEL_ID = "tracking_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        linearAccelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        /*
        // --- GPS 初始化 ---
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                val location = locationResult.lastLocation ?: return
                if (startGpsLat == null) {
                    startGpsLat = location.latitude
                    startGpsLon = location.longitude
                }
                val dLat = location.latitude - startGpsLat!!
                val dLon = location.longitude - startGpsLon!!
                val latRad = Math.toRadians(startGpsLat!!)
                val gpsY = (dLat * 111000).toFloat()
                val gpsX = (dLon * 111320 * cos(latRad)).toFloat()
                saveLocationToDatabase(gpsX, gpsY, "GPS")
            }
        }
        */
    }

    private fun startSensors() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAccelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun stopSensors() {
        sensorManager.unregisterListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_TRACKING -> startTracking()
            ACTION_STOP_TRACKING -> stopTracking()
            ACTION_RESET_TRACKING -> resetTracking()
        }
        return START_STICKY
    }

    private fun resetTracking() {
        currentX = 0f
        currentY = 0f
        velocityX = 0f
        velocityY = 0f
        lastTimestampNS = 0
        slowAzimuthX = 0f
        slowAzimuthY = 1f
        baseAzimuthRad = null

        serviceScope.launch {
            val db = TrackDatabase.getDatabase(this@TrackingService)
            val newTrack = TrackEntity(startTime = System.currentTimeMillis())
            currentTrackId = db.trackDao().insertTrack(newTrack)
            saveLocationToDatabase(currentX, currentY, "SENSOR")
        }
    }

    //@SuppressLint("MissingPermission")
    private fun startTracking() {
        createNotificationChannel()
        
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("室內矩形軌跡追蹤")
            .setContentText("正在進行加速度二次積分抗漂移追蹤...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        currentX = 0f
        currentY = 0f
        velocityX = 0f
        velocityY = 0f
        lastTimestampNS = 0
        slowAzimuthX = 0f
        slowAzimuthY = 1f
        baseAzimuthRad = null

        /*
        startGpsLat = null
        startGpsLon = null
        */

        serviceScope.launch {
            val db = TrackDatabase.getDatabase(this@TrackingService)
            val newTrack = TrackEntity(startTime = System.currentTimeMillis())
            currentTrackId = db.trackDao().insertTrack(newTrack)
            
            saveLocationToDatabase(currentX, currentY, "SENSOR")
        }

        startSensors()

        /*
        // --- 啟動 GPS ---
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateIntervalMillis(1000).build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
        */
    }

    private fun saveLocationToDatabase(x: Float, y: Float, source: String) {
        if (currentTrackId == -1L) return
        
        serviceScope.launch {
            val db = TrackDatabase.getDatabase(this@TrackingService)
            val point = TrackPointEntity(
                trackId = currentTrackId,
                x = x,
                y = y,
                source = source,
                timestamp = System.currentTimeMillis()
            )
            db.trackDao().insertTrackPoint(point)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientationAngles = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                currentAzimuthRad = orientationAngles[0]

                // 低通濾波器計算平滑轉向 (用於轉彎偵測)
                val alpha = 0.05f
                slowAzimuthX = slowAzimuthX * (1 - alpha) + sin(currentAzimuthRad) * alpha
                slowAzimuthY = slowAzimuthY * (1 - alpha) + cos(currentAzimuthRad) * alpha
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (lastTimestampNS == 0L) {
                    lastTimestampNS = event.timestamp
                    return
                }
                val dt = (event.timestamp - lastTimestampNS) / 1_000_000_000f
                lastTimestampNS = event.timestamp

                val noiseThresh = TrackingConfig.noiseThreshold.value
                val decay = TrackingConfig.velocityDecay.value
                val distScale = TrackingConfig.distanceScale.value

                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val magnitude = sqrt((ax*ax + ay*ay + az*az).toDouble()).toFloat()

                val turnStabilizerEnabled = TrackingConfig.turnStabilizer.value
                var isTurning = false
                if (turnStabilizerEnabled) {
                    val slowAzimuthRad = kotlin.math.atan2(slowAzimuthX.toDouble(), slowAzimuthY.toDouble()).toFloat()
                    var diff = Math.abs(currentAzimuthRad - slowAzimuthRad)
                    if (diff > Math.PI) diff = (2 * Math.PI - diff).toFloat()
                    
                    // 若短時間內方向改變超過 12 度 (約 0.2 rad)，判定為轉彎中
                    isTurning = diff > 0.2f
                }

                // 矩形鎖定 (Cardinal Snapping)
                if (baseAzimuthRad == null) {
                    baseAzimuthRad = currentAzimuthRad
                }
                var effectiveAzimuth = currentAzimuthRad
                if (TrackingConfig.cardinalSnapping.value) {
                    var diff = effectiveAzimuth - baseAzimuthRad!!
                    while (diff > Math.PI) diff -= (2 * Math.PI).toFloat()
                    while (diff < -Math.PI) diff += (2 * Math.PI).toFloat()
                    val snappedDiff = Math.round(diff / (Math.PI / 2)) * (Math.PI / 2).toFloat()
                    effectiveAzimuth = baseAzimuthRad!! + snappedDiff
                }

                if (magnitude > noiseThresh && !isTurning) {
                    val worldAx = magnitude * sin(effectiveAzimuth)
                    val worldAy = magnitude * cos(effectiveAzimuth)

                    velocityX += worldAx * dt
                    velocityY += worldAy * dt

                    // 只有在超過門檻（真正移動）時，才套用衰減，避免無限疊加
                    velocityX *= decay
                    velocityY *= decay
                } else {
                    // 【零速度更新 ZUPT (Zero Velocity Update)】
                    // 如果加速度小於門檻，代表處於靜止狀態或等速滑行，
                    // 行人通常沒有等速滑行，因此強制將速度歸零，瞬間斬斷漂移！
                    velocityX = 0f
                    velocityY = 0f
                }

                val dx = velocityX * dt * distScale
                val dy = velocityY * dt * distScale

                if (dx != 0f || dy != 0f) {
                    currentX += dx
                    currentY += dy
                    saveLocationToDatabase(currentX, currentY, "SENSOR")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun stopTracking() {
        stopSensors()
        
        /*
        // --- 停止 GPS ---
        fusedLocationClient.removeLocationUpdates(locationCallback)
        */

        serviceScope.launch {
            if (currentTrackId != -1L) {
                currentTrackId = -1L
            }
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
