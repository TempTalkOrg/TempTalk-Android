package com.difft.android.setting

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil

enum class AppStore(val storePackage: String, val webListingPrefix: String) {
    GooglePlay("com.android.vending", "https://play.google.com/store/apps/details?id="),
    FDroid("org.fdroid.fdroid", "https://f-droid.org/packages/"),
}





class StoreUpdateDelivery(internal val store: AppStore) : UpdateDelivery {

    override val isInProgress: Boolean = false

    override fun attach(activity: FragmentActivity, onForceHandOff: () -> Unit) = Unit

    
    
    override fun deliver(context: Context, offer: UpdateOffer, isForce: Boolean, onForceHandOff: () -> Unit) {
        L.i { "[UpdateDelivery] open store=${store.name} force=$isForce" }
        val opened = openStoreApp(context) || openWebListing(context)
        if (!opened) {
            ToastUtil.show(R.string.status_upgrade_install_failed)
        }
    }

    private fun openStoreApp(context: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, "market://details?id=${context.packageName}".toUri())
            .setPackage(store.storePackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        L.w { "[UpdateDelivery] open ${store.name} app failed: ${e.stackTraceToString()}" }
        false
    }

    private fun openWebListing(context: Context): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, "${store.webListingPrefix}${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        L.w { "[UpdateDelivery] open ${store.name} web listing failed: ${e.stackTraceToString()}" }
        false
    }
}
