package com.filespy.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Takes a 360° panorama: the user turns on the spot, the app grabs a frame every
 * STEP degrees (using the rotation sensor), then stitches the centre strip of each
 * frame into one wide cylindrical image and saves it to Pictures/FileSPY.
 * The JPEG gets Google "GPano" XMP tags so Google Photos / Street View-style
 * viewers can open it as a 360° photo.
 */
class PanoActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var btnStart: Button
    private lateinit var btnOpen: Button
    private lateinit var sensorManager: SensorManager

    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val stepDeg = 20f
    private val total = (360f / stepDeg).toInt()          // 18 photos
    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var currentAz = 0f
    @Volatile private var nextIndex = 0
    private var startAz = 0f
    private val frames = ArrayList<Bitmap>()
    private val angles = ArrayList<Float>()                // degrees, 0..360
    private var fovRad = Math.toRadians(65.0)              // horizontal FOV (portrait)
    private var savedUri: Uri? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else {
            Toast.makeText(this, "Camera permission is needed", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pano)
        previewView = findViewById(R.id.preview)
        status = findViewById(R.id.status)
        btnStart = findViewById(R.id.btnStart)
        btnOpen = findViewById(R.id.btnOpen)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        fovRad = readHorizontalFov()
        btnStart.setOnClickListener { begin() }
        btnOpen.setOnClickListener {
            savedUri?.let {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        permission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: run { status.text = "This phone has no rotation sensor." }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // ---------- camera ----------
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val selector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                ).build()
            imageCapture = ImageCapture.Builder()
                .setResolutionSelector(selector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    /** Horizontal field of view of a portrait photo, from the camera hardware specs. */
    private fun readHorizontalFov(): Double {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val focal = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                val size = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                if (focal != null && size != null) {
                    return 2 * atan(min(size.width, size.height) / (2.0 * focal))
                }
            }
        } catch (_: Exception) { }
        return Math.toRadians(65.0)
    }

    // ---------- capture flow ----------
    private fun begin() {
        frames.forEach { it.recycle() }
        frames.clear(); angles.clear()
        nextIndex = 0
        startAz = currentAz
        busy = false
        running = true
        btnStart.isEnabled = false
        btnOpen.visibility = android.view.View.GONE
        status.text = "Turn slowly to the RIGHT ➜  0 / $total"
    }

    override fun onSensorChanged(e: SensorEvent) {
        val r = FloatArray(9); val r2 = FloatArray(9); val o = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, e.values)
        // phone held upright (camera pointing at the horizon)
        SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, r2)
        SensorManager.getOrientation(r2, o)
        currentAz = Math.toDegrees(o[0].toDouble()).toFloat()

        if (!running || busy) return
        val delta = relativeAngle()
        val target = nextIndex * stepDeg
        if (nextIndex == 0 || (delta >= target && delta < target + 60f)) capture()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun relativeAngle(): Float = (((currentAz - startAz) % 360f) + 360f) % 360f

    private fun capture() {
        val ic = imageCapture ?: return
        busy = true
        ic.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp: Bitmap
                try {
                    val raw = image.toBitmap()
                    val m = Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }
                    bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                } finally {
                    image.close()
                }
                frames.add(bmp)
                angles.add(if (frames.size == 1) 0f else relativeAngle())
                nextIndex++
                runOnUiThread { status.text = "Keep turning RIGHT ➜  $nextIndex / $total" }
                if (nextIndex >= total) finishCapture() else busy = false
            }

            override fun onError(exc: ImageCaptureException) {
                busy = false
                runOnUiThread { Toast.makeText(this@PanoActivity, "Capture failed", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    private fun finishCapture() {
        running = false
        runOnUiThread { status.text = "Stitching your 360° photo…" }
        Thread {
            try {
                val pano = stitch()
                val uri = save(pano)
                savedUri = uri
                runOnUiThread {
                    status.text = "✅ Saved to Pictures/FileSPY (${pano.width}×${pano.height})"
                    btnOpen.visibility = android.view.View.VISIBLE
                    btnStart.isEnabled = true
                    btnStart.text = "Take another"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Failed: ${e.message}"
                    btnStart.isEnabled = true
                }
            }
        }.start()
    }

    // ---------- stitching (cylindrical projection) ----------
    private fun stitch(): Bitmap {
        val w0 = frames[0].width
        val h = frames[0].height
        val f = (w0 / 2.0) / tan(fovRad / 2)                 // focal length in pixels
        val width = (2 * PI * f).roundToInt()
        val out = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val n = frames.size

        for (i in 0 until n) {
            val a = Math.toRadians(angles[i].toDouble())
            val prev = if (i == 0) Math.toRadians(angles[n - 1].toDouble()) - 2 * PI
                       else Math.toRadians(angles[i - 1].toDouble())
            val next = if (i == n - 1) Math.toRadians(angles[0].toDouble()) + 2 * PI
                       else Math.toRadians(angles[i + 1].toDouble())
            val x0 = floor((prev + a) / 2 * f).toInt()       // strip covers half-way to each neighbour
            val x1 = ceil((a + next) / 2 * f).toInt()
            for (x in x0 until x1) {
                val theta = x / f - a
                val sx = (w0 / 2.0 + f * tan(theta)).roundToInt().coerceIn(0, w0 - 1)
                val dx = ((x % width) + width) % width
                canvas.drawBitmap(frames[i], Rect(sx, 0, sx + 1, h), Rect(dx, 0, dx + 1, h), null)
            }
        }
        frames.forEach { it.recycle() }
        frames.clear()
        return out
    }

    // ---------- saving ----------
    private fun save(pano: Bitmap): Uri {
        val bos = ByteArrayOutputStream()
        pano.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        val jpeg = injectXmp(bos.toByteArray(), pano.width, pano.height)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "FileSPY_360_$stamp.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FileSPY")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Cannot create file")
        contentResolver.openOutputStream(uri)!!.use { it.write(jpeg) }
        return uri
    }

    /** Adds Google panorama (GPano) XMP so photo viewers treat it as a 360° photo. */
    private fun injectXmp(jpeg: ByteArray, w: Int, h: Int): ByteArray {
        val fullH = w / 2
        val top = (fullH - h) / 2
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"><GPano:UsePanoramaViewer>True</GPano:UsePanoramaViewer><GPano:ProjectionType>equirectangular</GPano:ProjectionType><GPano:FullPanoWidthPixels>$w</GPano:FullPanoWidthPixels><GPano:FullPanoHeightPixels>$fullH</GPano:FullPanoHeightPixels><GPano:CroppedAreaImageWidthPixels>$w</GPano:CroppedAreaImageWidthPixels><GPano:CroppedAreaImageHeightPixels>$h</GPano:CroppedAreaImageHeightPixels><GPano:CroppedAreaLeftPixels>0</GPano:CroppedAreaLeftPixels><GPano:CroppedAreaTopPixels>$top</GPano:CroppedAreaTopPixels></rdf:Description></rdf:RDF></x:xmpmeta>""".toByteArray(Charsets.UTF_8)
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val len = 2 + header.size + xmp.size
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, 2)                                  // SOI
        out.write(0xFF); out.write(0xE1)                       // APP1
        out.write(len shr 8); out.write(len and 0xFF)
        out.write(header); out.write(xmp)
        out.write(jpeg, 2, jpeg.size - 2)
        return out.toByteArray()
    }
}
