//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.telephony.Rlog

class WfcSubscriptionSettingMonitor(
    private val tag: String,
    private val ctxt: Context,
    handler: Handler,
    private val subId: Int,
    private val onWfcDisabled: (String) -> Unit,
) {
    private val simInfoUri = Uri.parse("content://telephony/siminfo")
    @Volatile private var observedWfcEnabled: Boolean? = null

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handleChanged("siminfo observer")
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            handleChanged("siminfo observer uri=$uri")
        }
    }

    fun start() {
        observedWfcEnabled = readEnabledProperty()
        Rlog.d(tag, "Initial WFC subscription setting enabled=$observedWfcEnabled")
        try {
            ctxt.contentResolver.registerContentObserver(simInfoUri, true, observer)
            Rlog.d(tag, "Registered WFC subscription setting observer")
        } catch (t: Throwable) {
            Rlog.d(tag, "Registering WFC subscription setting observer failed", t)
        }
    }

    private fun readEnabledProperty(): Boolean? {
        return try {
            ctxt.contentResolver.query(
                simInfoUri,
                arrayOf("_id", "wfc_ims_enabled"),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndex("_id")
                val wfcColumn = cursor.getColumnIndex("wfc_ims_enabled")
                if (idColumn < 0 || wfcColumn < 0) {
                    Rlog.w(tag, "siminfo is missing WFC columns id=$idColumn wfc=$wfcColumn")
                    return null
                }
                while (cursor.moveToNext()) {
                    if (cursor.getInt(idColumn) == subId) {
                        return cursor.getInt(wfcColumn) == 1
                    }
                }
                Rlog.w(tag, "No siminfo row found for subId=$subId while checking WFC")
                null
            }
        } catch (t: Throwable) {
            Rlog.d(tag, "Reading WFC subscription property failed", t)
            null
        }
    }

    private fun handleChanged(reason: String) {
        val enabled = readEnabledProperty() ?: return
        val old = observedWfcEnabled
        observedWfcEnabled = enabled
        if (old == enabled) return
        Rlog.d(tag, "WFC subscription setting changed old=$old enabled=$enabled reason=$reason")
        if (old == true && !enabled) {
            onWfcDisabled(reason)
        }
    }
}
