package com.whyfi.app.usbmon

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Must match the manifest provider and its restricted mission directory paths. */
internal object MonitorEvidenceFiles {
    const val AUTHORITY_SUFFIX = ".monitorfiles"

    fun intent(context: Context, file: File, type: String, action: String): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        return Intent(action).apply {
            setDataAndType(uri, type)
            if (action == Intent.ACTION_SEND) putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
