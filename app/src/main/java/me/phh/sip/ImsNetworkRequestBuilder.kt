//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.SystemProperties

object ImsNetworkRequestBuilder {
    private const val PROP_USE_DEFAULT_DATA = "persist.ims.use_default_data"

    fun useDefaultDataBearer(): Boolean =
        SystemProperties.getBoolean(PROP_USE_DEFAULT_DATA, false)

    fun buildForSubscription(subId: Int): NetworkRequest {
        val builder = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .setNetworkSpecifier(
                TelephonyNetworkSpecifier.Builder()
                    .setSubscriptionId(subId)
                    .build()
            )

        if (useDefaultDataBearer()) {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            builder.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
        }

        return builder.build()
    }
}
