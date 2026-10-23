package com.filespy.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var reportView: TextView

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) analyze(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        PDFBoxResourceLoader.init(applicationContext)

        reportView = findViewById(R.id.report)
        findViewById<Button>(R.id.btnChoose).setOnClickListener {
            picker.launch(arrayOf("application/pdf", "image/*"))
        }
        findViewById<Button>(R.id.btnCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("File SPY report", reportView.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun analyze(uri: Uri) {
        reportView.text = "Analyzing…"
        Thread {
            val text = try {
                buildReport(uri)
            } catch (e: Exception) {
                "Could not read this file:\n${e.message}"
            }
            runOnUiThread { reportView.text = text }
        }.start()
    }

    private val fmt get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())

    private fun buildReport(uri: Uri): String {
        val sb = StringBuilder()
        val mime = contentResolver.getType(uri) ?: "unknown"

        sb.section("FILE")
        var name = "?"; var size = -1L; var modified = 0L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
                c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
                c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }
                    ?.let { modified = c.getLong(it) }
            }
        }
        sb.line("Name", name)
        sb.line("Type", mime)
        if (size >= 0) sb.line("Size", "%,d bytes (%.2f MB)".format(size, size / 1048576.0))
        if (modified > 0) sb.line("File last modified", fmt.format(Date(modified)))

        when {
            mime.startsWith("image/") -> imageInfo(uri, sb)
            mime == "application/pdf" -> pdfInfo(uri, sb)
            else -> sb.line("Note", "Only PDF and image files are supported")
        }
        return sb.toString()
    }

    private fun imageInfo(uri: Uri, sb: StringBuilder) {
        contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            fun t(tag: String) = exif.getAttribute(tag)

            sb.section("WHO / WHAT MADE IT")
            sb.line("Artist / Author", t(ExifInterface.TAG_ARTIST))
            sb.line("Copyright", t(ExifInterface.TAG_COPYRIGHT))
            sb.line("Camera make", t(ExifInterface.TAG_MAKE))
            sb.line("Camera model", t(ExifInterface.TAG_MODEL))
            sb.line("Software", t(ExifInterface.TAG_SOFTWARE))
            sb.line("Lens", t(ExifInterface.TAG_LENS_MODEL))

            sb.section("WHEN")
            sb.line("Date taken", t(ExifInterface.TAG_DATETIME_ORIGINAL))
            sb.line("Date digitized", t(ExifInterface.TAG_DATETIME_DIGITIZED))
            sb.line("Date modified", t(ExifInterface.TAG_DATETIME))
            sb.line("Timezone offset", t(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))

            sb.section("WHERE")
            val ll = exif.latLong
            if (ll != null) {
                sb.line("GPS", "%.6f, %.6f".format(ll[0], ll[1]))
                val alt = exif.getAltitude(Double.NaN)
                sb.line("Altitude", if (alt.isNaN()) null else "%.1f m".format(alt))
                sb.line("Map", "https://maps.google.com/?q=${ll[0]},${ll[1]}")
                sb.line("Address", reverseGeocode(ll[0], ll[1]))
            } else {
                sb.line("GPS", "No location data in this image")
            }

            sb.section("IMAGE")
            sb.line("Width", t(ExifInterface.TAG_IMAGE_WIDTH))
            sb.line("Height", t(ExifInterface.TAG_IMAGE_LENGTH))
            sb.line("Exposure", t(ExifInterface.TAG_EXPOSURE_TIME))
            sb.line("Aperture", t(ExifInterface.TAG_F_NUMBER))
            sb.line("ISO", t(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
            sb.line("Flash", t(ExifInterface.TAG_FLASH))
        }
    }

    private fun pdfInfo(uri: Uri, sb: StringBuilder) {
        contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream).use { doc ->
                val i = doc.documentInformation
                sb.section("WHO / WHAT MADE IT")
                sb.line("Author", i.author)
                sb.line("Creator app", i.creator)
                sb.line("Producer", i.producer)
                sb.line("Title", i.title)
                sb.line("Subject", i.subject)
                sb.line("Keywords", i.keywords)

                sb.section("WHEN")
                sb.line("Created", i.creationDate?.let { fmtCal(it) })
                sb.line("Modified", i.modificationDate?.let { fmtCal(it) })

                sb.section("WHERE")
                sb.line("Location", "PDF files don't normally store a location")

                sb.section("DOCUMENT")
                sb.line("Pages", doc.numberOfPages.toString())
                sb.line("PDF version", doc.version.toString())
                sb.line("Encrypted", doc.isEncrypted.toString())
                val standard = setOf("Author", "Creator", "Producer", "Title", "Subject",
                    "Keywords", "CreationDate", "ModDate", "Trapped")
                i.metadataKeys.filter { it !in standard }
                    .forEach { sb.line("Custom: $it", i.getCustomMetadataValue(it)) }
            }
        }
    }

    private fun fmtCal(c: Calendar) = fmt.format(c.time)

    @Suppress("DEPRECATION")
    private fun reverseGeocode(lat: Double, lon: Double): String? = try {
        Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
            ?.firstOrNull()?.getAddressLine(0)
    } catch (e: Exception) {
        "(needs internet to look up address)"
    }

    private fun StringBuilder.section(title: String) {
        append("\n══ ").append(title).append(" ══\n")
    }

    private fun StringBuilder.line(label: String, value: String?) {
        append(label.padEnd(18)).append(": ")
            .append(if (value.isNullOrBlank()) "—" else value).append('\n')
    }
}
