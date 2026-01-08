package com.example.qr_scanner

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.view.animation.OvershootInterpolator

class OverlayService : Service() {
    private var mediaProjection: MediaProjection? = null
    private lateinit var windowManager: WindowManager
    private lateinit var floatingButton: ImageView
    private lateinit var imageReader: ImageReader
    private var isPopupShowing = false // state for popup

    override fun onBind(intent: Intent?): IBinder? = null

    // get data MediaProjection from MainActivity
    @SuppressLint("ServiceCast")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_CAPTURE") {
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("data", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("data")
            }

            if (data != null) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpManager.getMediaProjection(resultCode, data)
            }
        }
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // Run Notifikasi Foreground
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "scanner_channel")
            .setContentTitle("QR Scanner Active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1, notification)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inisilasation Floating Button
        floatingButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setColorFilter(Color.WHITE)
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A73E8"))
                setStroke(2, Color.WHITE)
            }
            background = shape
            elevation = 15f
            val size = (56 * resources.displayMetrics.density).toInt()
            layoutParams = WindowManager.LayoutParams(size, size)
            setPadding(35, 35, 35, 35)
            alpha = 0.9f
        }

        // Define Position Parameters on Screen
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // Declare Movement Helper Variables
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        // (Drag & Scale Combination)
        floatingButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Efek Scale Smooth (Tailwind scale-90)
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()

                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingButton, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    // Efek Scale Kembali (Tailwind scale-100)
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()

                    val diffX = event.rawX - initialTouchX
                    val diffY = event.rawY - initialTouchY
                    if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                        v.performClick() // Memicu onClickListener di bawah
                    }
                    true
                }
                else -> false
            }
        }


        floatingButton.setOnClickListener {
            scanScreen()
        }

        windowManager.addView(floatingButton, params)
    }

    private fun scanScreen() {
        if (mediaProjection == null) {
            Toast.makeText(this, "Izin capture hilang. Silakan buka ulang aplikasi.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val metrics = resources.displayMetrics
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 1)

            val virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenScanner", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, null
            )

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * metrics.widthPixels

                val bitmap = Bitmap.createBitmap(
                    metrics.widthPixels + rowPadding / pixelStride,
                    metrics.heightPixels, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                image.close()
                reader.setOnImageAvailableListener(null, null)
                virtualDisplay?.release()

                processQrCode(bitmap)
            }, Handler(Looper.getMainLooper()))

        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(this, "Gagal: Media Projection bentrok dengan aplikasi lain (mungkin sedang rekam layar)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Terjadi kesalahan sistem saat menangkap layar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processQrCode(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val scanner = BarcodeScanning.getClient()

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    val result = barcodes[0].rawValue ?: ""
                    // Panggil aksi di sini
                    handleScanResult(result)
                } else {
                    Log.d("SCANNER", "QR tidak ditemukan")
                }
            }
    }

    @SuppressLint("ServiceCast")
    private fun vibratePhone() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(200)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "scanner_channel", "Scanner Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    @SuppressLint("ServiceCast")
    private fun handleScanResult(url: String) {
        // Jalankan di Thread Utama agar Popup bisa muncul
        Handler(Looper.getMainLooper()).post {
            showResultPopup(url)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showResultPopup(url: String) {
        if (isPopupShowing) return
        isPopupShowing = true

        // Background Card with Border Radius
        val cardBackground = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = 40f
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(60, 50, 60, 60)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                elevation = 30f
            }

            // Animation Scale Up
            scaleX = 0.5f
            scaleY = 0.5f
            alpha = 0f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(OvershootInterpolator())
                .start()
        }

        // Title & Close Button
        val header = RelativeLayout(this)
        val title = TextView(this).apply {
            text = "QR Terdeteksi"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#202124"))
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.GRAY)
            setPadding(20, 10, 20, 10)
            setOnClickListener {
                windowManager.removeView(container)
                isPopupShowing = false
            }
        }
        val closeParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
        ).apply { addRule(RelativeLayout.ALIGN_PARENT_END) }

        header.addView(title)
        header.addView(closeBtn, closeParams)

        // URL Text
        val urlBox = TextView(this).apply {
            text = url
            textSize = 14f
            setTextColor(Color.parseColor("#1A73E8"))
            setPadding(30, 30, 30, 30)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END

            // Border radius untuk kotak URL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F1F3F4"))
                cornerRadius = 20f
            }

            val marginParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 40, 0, 50) }
            layoutParams = marginParams
        }

        // Action Button
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        // Button Copy
        val btnCopy = Button(this).apply {
            text = "Salin"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.parseColor("#5F6368"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("QR", url)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Link disalin", Toast.LENGTH_SHORT).show()
            }
        }

        // Button Redirect
        val btnOpen = Button(this).apply {
            text = "Buka Link"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#1A73E8"))
                cornerRadius = 25f
            }

            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(20, 0, 0, 0) }
            layoutParams = btnParams

            setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
                windowManager.removeView(container)
                isPopupShowing = false
            }
        }

        // Insert buttons into the button layout
        buttonLayout.addView(btnCopy)
        buttonLayout.addView(btnOpen)

        // Put all elements into the main container
        container.addView(header)
        container.addView(urlBox)
        container.addView(buttonLayout)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.6f
        }

        windowManager.addView(container, params)
        vibratePhone()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingButton.isInitialized) windowManager.removeView(floatingButton)
    }
}