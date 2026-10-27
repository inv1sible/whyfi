package com.whyfi.app.usbmon

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Must match the manifest provider and its restricted mission directory paths. */
internal object MonitorEvidenceFiles {
    const val AUTHORITY_SUFFIX = ".monitorfiles"

    fun exportName(mission: MonitorMission.Stored, file: File): String {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(mission.dateMs))
        return "whyfi-$date-${mission.directory.name.take(8)}-${file.name}"
    }

    fun intent(context: Context, file: File, type: String, action: String, displayName: String): Intent {
        // A display-name alias avoids copying a potentially 200 MB PCAP just to rename an export.
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file, displayName)
        return Intent(action).apply {
            setDataAndType(uri, type)
            if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, displayName)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
