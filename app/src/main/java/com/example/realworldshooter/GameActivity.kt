package com.example.realworldshooter

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import android.graphics.Rect
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.realworldshooter.databinding.ActivityGameBinding
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.gson.Gson
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GameActivity : AppCompatActivity() {
    enum class Weapon(val damage: Int) {
        PISTOL(10),
        SHOTGUN(25),
        SNIPER(15),
        SMG(15),
        AR(20)
    }
    private var faceBox: Rect? = null
    private var lastProxy: ImageProxy? = null
    private var currentWeapon = Weapon.PISTOL
    private var hoveredWeapon: Weapon? = null
    private var isDragging = false
    private var startY = 0f
    private lateinit var binding: ActivityGameBinding
    private lateinit var soundPool: SoundPool
    private var gunshotSoundId: Int = 0
    private lateinit var cameraExecutor: ExecutorService
    private var faceDetected = false
    private val faceDetector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .build()
        FaceDetection.getClient(opts)
    }
    private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    private lateinit var connectionsClient: ConnectionsClient
    private var opponentEndpointId: String? = null
    private val gson = Gson()
    private var myHealth = 100
    private var opponentHealth = 100
    private val DAMAGE = 10
    private val SHOOT_COOLDOWN_MS = 700L
    private var lastShotTime = 0L
    private var isGameOver = false

    // Ammo system
    private val maxAmmo = mapOf(
        Weapon.PISTOL to 12,
        Weapon.SHOTGUN to 6,
        Weapon.SNIPER to 3,
        Weapon.SMG to 30,
        Weapon.AR to 20
    )

    private val ammo = mutableMapOf(
        Weapon.PISTOL to 12,
        Weapon.SHOTGUN to 6,
        Weapon.SNIPER to 3,
        Weapon.SMG to 30,
        Weapon.AR to 20
    )

    private var isReloading = false
    private val reloadTimeMs = 1500L

    private val SERVICE_ID by lazy { packageName }
    private val REQUIRED_PERMISSIONS: Array<String>
        get() {
            val list = mutableListOf<String>()
            list.add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                // For Android 13+, runtime permission NEARBY_WIFI_DEVICES will be requested separately
                // but we still include it in the manifest; do not add here to avoid compile confusion.
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                list.add(Manifest.permission.BLUETOOTH_SCAN)
                list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
                list.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            return list.toTypedArray()
        }
    private val PERMISSION_CODE = 1001
    private val PERMISSION_CODE_NEARBY = 1002
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameBinding.inflate(layoutInflater)
        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .build()
        gunshotSoundId = soundPool.load(this, R.raw.gunshot, 1)
        setContentView(binding.root)
        cameraExecutor = Executors.newSingleThreadExecutor()
        connectionsClient = Nearby.getConnectionsClient(this)
        requestInitialPermissionsIfNeeded()
        val mode = intent.getStringExtra("mode")
        binding.root.post {
            if (ensureAllNearbyPermissionsPresent()) {
                if (mode == "host") startHost()
                else if (mode == "join") startJoin()
            }
        }
        binding.shootButton.setOnTouchListener { v, event ->
            when (event.action) {

                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    isDragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = startY - event.rawY

                    // drag UP detected
                    if (dy > 40 && !isDragging) {
                        isDragging = true
                        showWeaponWheel()
                        hoveredWeapon = null
                    }

                    if (isDragging) {
                        handleWeaponHover(event.rawX, event.rawY)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        hoveredWeapon?.let { selectWeapon(it) }
                        hideWeaponWheel()
                    } else {
                        // normal tap → SHOOT
                        onShootPressed()
                    }
                }
            }
            true
        }
        binding.reloadButton.setOnClickListener {
            reloadWeapon()
        }

        updateUI()
    }
    private fun hasBasePermissions(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    private fun hasNearbyWifiDevicesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    private fun requestInitialPermissionsIfNeeded() {
        if (!hasBasePermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_CODE)
            debug("Requesting base permissions")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNearbyWifiDevicesPermission()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
                PERMISSION_CODE_NEARBY
            )
            debug("Requesting NEARBY_WIFI_DEVICES permission")
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_CODE) {
            if (hasBasePermissions()) {
                debug("Base permissions granted")
            } else {
                debug("Base permissions denied. App needs camera + bluetooth perms.")
                Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == PERMISSION_CODE_NEARBY) {
            if (hasNearbyWifiDevicesPermission()) {
                debug("NEARBY_WIFI_DEVICES granted")
            } else {
                debug("NEARBY_WIFI_DEVICES denied — discovery/advertising may fail on Android 13+")
                Toast.makeText(this, "Nearby Wifi Devices permission required for Nearby Connections", Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun startHost() {
        debug("Host button pressed")
        if (!ensureAllNearbyPermissionsPresent()) return
        startCamera()
        startAdvertising()
    }
    private fun startJoin() {
        debug("Join button pressed")
        if (!ensureAllNearbyPermissionsPresent()) return
        startCamera()
        startDiscovery()
    }
    private fun ensureAllNearbyPermissionsPresent(): Boolean {
        if (!hasBasePermissions()) {
            debug("Missing base permissions — requesting")
            requestInitialPermissionsIfNeeded()
            return false
        }
        if (!hasNearbyWifiDevicesPermission()) {
            debug("Missing NEARBY_WIFI_DEVICES — requesting (Android 13+)")
            requestInitialPermissionsIfNeeded()
            return false
        }
        return true
    }
    private fun showWeaponWheel() {
        binding.weaponWheel.apply {
            visibility = View.VISIBLE
            animate().alpha(1f).setDuration(200).start()
        }
    }
    private fun hideWeaponWheel() {
        binding.weaponWheel.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.weaponWheel.visibility = View.GONE
            }.start()
    }
    private fun selectWeapon(weapon: Weapon) {
        currentWeapon = weapon
        clearHighlights()
        when (weapon) {
            Weapon.PISTOL -> highlight(binding.weaponPistol)
            Weapon.SHOTGUN -> highlight(binding.weaponShotgun)
            Weapon.SNIPER -> highlight(binding.weaponSniper)
            Weapon.SMG -> highlight(binding.weaponSmg)
            Weapon.AR -> highlight(binding.weaponAr)
        }
        hideWeaponWheel()
    }
    private fun updateHoverHighlight() {
        clearHighlights()
        when (hoveredWeapon) {
            Weapon.PISTOL -> highlight(binding.weaponPistol)
            Weapon.SHOTGUN -> highlight(binding.weaponShotgun)
            Weapon.SNIPER -> highlight(binding.weaponSniper)
            Weapon.SMG -> highlight(binding.weaponSmg)
            Weapon.AR -> highlight(binding.weaponAr)
            null -> {}
        }
    }
    private fun handleWeaponHover(x: Float, y: Float) {
        val weapons = listOf(
            Weapon.PISTOL to binding.weaponPistol,
            Weapon.SHOTGUN to binding.weaponShotgun,
            Weapon.SNIPER to binding.weaponSniper,
            Weapon.SMG to binding.weaponSmg,
            Weapon.AR to binding.weaponAr
        )
        var newHover: Weapon? = null
        for ((weapon, view) in weapons) {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val left = location[0]
            val top = location[1]
            val right = left + view.width
            val bottom = top + view.height
            if (x >= left && x <= right && y >= top && y <= bottom) {
                newHover = weapon
                break
            }
        }
        if (newHover != hoveredWeapon) {
            hoveredWeapon = newHover
            updateHoverHighlight()
        }
    }
    private fun highlight(view: View) {
        view.animate()
            .scaleX(1.3f)
            .scaleY(1.3f)
            .setDuration(150)
            .start()

        view.setBackgroundResource(R.drawable.weapon_glow)
    }
    private fun clearHighlights() {
        val all = listOf(
            binding.weaponPistol,
            binding.weaponShotgun,
            binding.weaponSniper,
            binding.weaponSmg,
            binding.weaponAr
        )
        for (v in all) {
            v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            v.setBackgroundResource(0)
        }
    }
    private fun onShootPressed() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastShotTime < SHOOT_COOLDOWN_MS) {
            debug("Cooldown active")
            return
        }
        lastShotTime = now
        if (!isCrosshairOnFace()) {
            debug("Crosshair not on face → cannot shoot")
            return
        }

        val id = opponentEndpointId ?: run {
            debug("NO CONNECTION → cannot shoot")
            return
        }
        debug("SHOT FIRED → sending damage")
        soundPool.play(gunshotSoundId, 1f, 1f, 1, 0, 1f)
        send(GameMessage("shoot", currentWeapon.damage))
        if (ammo[currentWeapon]!! <= 0) {
            debug("NO AMMO → Reload required")
            return
        }

        ammo[currentWeapon] = ammo[currentWeapon]!! - 1
        if (isGameOver) return

        updateUI()

    }
    private fun reloadWeapon() {
        if (isReloading) return
        if (ammo[currentWeapon] == maxAmmo[currentWeapon]) {
            debug("Ammo full")
            return
        }

        isReloading = true
        debug("Reloading...")

        binding.reloadButton.isEnabled = false

        binding.reloadButton.postDelayed({
            ammo[currentWeapon] = maxAmmo[currentWeapon]!!
            debug("Reload complete")
            updateUI()
            binding.reloadButton.isEnabled = true
            isReloading = false
        }, reloadTimeMs)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun startCamera() {
        debug("Starting camera...")
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(binding.viewFinder.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analyzer.setAnalyzer(cameraExecutor) { proxy ->
                    detectFaces(proxy)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
                debug("Camera bound to lifecycle")
            } catch (t: Throwable) {
                debug("Camera start failed: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }
    @OptIn(ExperimentalGetImage::class)
    private fun detectFaces(proxy: ImageProxy) {
        lastProxy = proxy

        val image = proxy.image ?: return proxy.close()
        val input = InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees)

        faceDetector.process(input)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    faceBox = faces.first().boundingBox
                } else {
                    faceBox = null
                }
            }
            .addOnCompleteListener { proxy.close() }
    }
    private fun mapBoxToScreen(box: Rect, proxy: ImageProxy): RectF {
        val previewWidth = binding.viewFinder.width.toFloat()
        val previewHeight = binding.viewFinder.height.toFloat()

        val rotation = proxy.imageInfo.rotationDegrees

        val imgW = if (rotation == 0 || rotation == 180) proxy.width.toFloat() else proxy.height.toFloat()
        val imgH = if (rotation == 0 || rotation == 180) proxy.height.toFloat() else proxy.width.toFloat()

        val scaleX = previewWidth / imgW
        val scaleY = previewHeight / imgH


        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY

        return RectF(left, top, right, bottom)
    }
    private fun isCrosshairOnFace(): Boolean {
        val box = faceBox ?: return false
        val proxy = lastProxy ?: return false

        // convert image-box → screen-box
        val screenBox = mapBoxToScreen(box, proxy)

        val cx = binding.crosshair.x + binding.crosshair.width / 2
        val cy = binding.crosshair.y + binding.crosshair.height / 2

        return screenBox.contains(cx, cy)
    }


    private fun startAdvertising() {
        debug("Starting advertising... (serviceId=$SERVICE_ID)")
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
                /* name= */ "PlayerHost",
            /* serviceId= */ SERVICE_ID,
            /* callback= */ connectionLifecycleCallback,
            /* options= */ options
        ).addOnSuccessListener {
            debug("Advertising SUCCESS")
        }.addOnFailureListener { e ->
            debug("Advertising FAILED → ${e.message}")
        }
    }
    private fun startDiscovery() {
        debug("Starting discovery... (serviceId=$SERVICE_ID)")
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            /* serviceId= */ SERVICE_ID,
            /* callback= */ endpointDiscoveryCallback,
            /* options= */ options
        ).addOnSuccessListener {
            debug("Discovery SUCCESS")
        }.addOnFailureListener { e ->
            debug("Discovery FAILED → ${e.message}")
        }
    }
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            debug("Endpoint found: id=$endpointId, name=${info.endpointName} → requesting connection")
            try {
                connectionsClient.requestConnection("JoinPlayer", endpointId, connectionLifecycleCallback)
            } catch (t: Throwable) {
                debug("requestConnection threw: ${t.message}")
            }
        }
        override fun onEndpointLost(endpointId: String) {
            debug("Endpoint lost: $endpointId")
        }
    }
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            debug("Connection initiated from $endpointId (name=${info.endpointName}) → accepting")
            try {
                connectionsClient.acceptConnection(endpointId, payloadCallback)
            } catch (t: Throwable) {
                debug("acceptConnection failed: ${t.message}")
            }
        }
        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val status = resolution.status
            debug("onConnectionResult status=${status.statusCode}")
            if (status.isSuccess) {
                opponentEndpointId = endpointId
                debug("CONNECTED to $endpointId")
                // sync health state
                send(GameMessage("health", health = myHealth))
            } else {
                debug("Connection failed with code=${status.statusCode}")
            }
        }
        override fun onDisconnected(endpointId: String) {
            debug("Disconnected: $endpointId")
            if (endpointId == opponentEndpointId) opponentEndpointId = null
        }
    }
    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: run {
                debug("Received non-bytes payload")
                return
            }
            val json = String(bytes)
            debug("Payload received: $json")
            try {
                val msg = gson.fromJson(json, GameMessage::class.java)
                handleMessage(msg)
            } catch (t: Throwable) {
                debug("Failed to parse payload: ${t.message}")
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
        }
    }
    private fun send(msg: GameMessage) {
        val id = opponentEndpointId ?: run {
            debug("SEND FAILED → no endpoint")
            return
        }
        val json = gson.toJson(msg)
        debug("Sending: $json")
        try {
            connectionsClient.sendPayload(id, Payload.fromBytes(json.toByteArray()))
        } catch (t: Throwable) {
            debug("sendPayload exception: ${t.message}")
        }
    }
    private fun handleMessage(msg: GameMessage) {
        if (isGameOver) return  // ignore all future messages

        when (msg.type) {

            "shoot" -> {
                debug("I GOT SHOT → Damage = ${msg.damage}")

                myHealth = (myHealth - msg.damage).coerceAtLeast(0)
                updateUI()

                // SEND BACK UPDATED HEALTH
                send(GameMessage("health", health = myHealth))

                // CHECK IF I DIED
                if (myHealth <= 0 && !isGameOver) {
                    isGameOver = true
                    showGameOver("You Lost 💀")
                    send(GameMessage("game_over"))
                }
            }

            "health" -> {
                debug("Opponent health update → ${msg.health}")
                opponentHealth = msg.health
                updateUI()

                // CHECK IF OPPONENT DIED
                if (opponentHealth <= 0 && !isGameOver) {
                    isGameOver = true
                    showGameOver("You Won 🎉")
                    send(GameMessage("game_over"))
                }
            }

            "game_over" -> {
                if (!isGameOver) {
                    isGameOver = true
                    showGameOver("Game Over")
                }
            }

            else -> debug("Unknown message type: ${msg.type}")
        }
    }
    private fun showGameOver(message: String) = runOnUiThread {
        try {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("GAME OVER")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Exit") { _, _ ->
                    finish()
                }
                .create()

            dialog.show()

        } catch (e: Exception) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun updateUI() = runOnUiThread {
        binding.myHealthText.text = "Your Health: $myHealth"
        binding.opponentHealthText.text = "Opponent Health: $opponentHealth"
        binding.ammoText.text = "Ammo: ${ammo[currentWeapon]}/${maxAmmo[currentWeapon]}"

    }
    private fun debug(msg: String) {
        Log.d("NEARBY_DEBUG", msg)
        runOnUiThread {
            binding.myHealthText.text = "Your Health: $myHealth\n$msg"
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        try {
            connectionsClient.stopAllEndpoints()
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
        } catch (t: Throwable) {
            Log.w("NEARBY_DEBUG", "stop calls threw: ${t.message}")
        }
        cameraExecutor.shutdown()
        faceDetector.close()
        soundPool.release()

    }
}
data class GameMessage(
    val type: String,
    val damage: Int = 0,
    val health: Int = 0
)
