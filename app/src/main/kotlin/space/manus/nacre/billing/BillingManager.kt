package space.manus.nacre.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*

/**
 * Google Play Billing manager for Nacre AI addon.
 *
 * SPEC: Nacre AI is a buy-once in-app purchase (¥500-800).
 * After purchase, user can download Whisper and LLM models.
 */
class BillingManager(private val context: Context) : PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_AI_ADDON = "nacre_ai_addon"
        private const val PREFS_NAME = "nacre_billing"
        private const val KEY_PURCHASED = "ai_addon_purchased"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var billingClient: BillingClient? = null
    private var productDetails: ProductDetails? = null

    var isPurchased: Boolean = prefs.getBoolean(KEY_PURCHASED, false)
        private set(value) {
            field = value
            prefs.edit().putBoolean(KEY_PURCHASED, value).apply()
        }

    var onPurchaseResult: ((Boolean) -> Unit)? = null

    fun initialize() {
        billingClient = BillingClient.newBuilder(context)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.i(TAG, "Billing client connected")
                    queryProduct()
                    queryExistingPurchases()
                } else {
                    Log.e(TAG, "Billing setup failed: ${result.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_ID_AI_ADDON)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()

        billingClient?.queryProductDetailsAsync(params) { result, productDetailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = productDetailsList.firstOrNull()
                Log.i(TAG, "Product details loaded: ${productDetails?.name}")
            }
        }
    }

    private fun queryExistingPurchases() {
        scope.launch {
            val client = billingClient ?: return@launch
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            val result = client.queryPurchasesAsync(params)
            if (result.billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val purchased = result.purchasesList.any { purchase ->
                    purchase.products.contains(PRODUCT_ID_AI_ADDON) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (purchased) {
                    isPurchased = true
                    // Acknowledge any unacknowledged purchases
                    result.purchasesList
                        .filter { !it.isAcknowledged && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .forEach { acknowledgePurchase(it) }
                }
            }
        }
    }

    /**
     * Launch purchase flow for Nacre AI addon.
     */
    fun launchPurchase(activity: Activity) {
        val details = productDetails
        if (details == null) {
            Log.e(TAG, "Product details not loaded")
            onPurchaseResult?.invoke(false)
            return
        }

        val offerToken = details.oneTimePurchaseOfferDetails
        if (offerToken == null) {
            Log.e(TAG, "No one-time purchase offer")
            onPurchaseResult?.invoke(false)
            return
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()

        billingClient?.launchBillingFlow(activity, flowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isPurchased = true
                        acknowledgePurchase(purchase)
                        onPurchaseResult?.invoke(true)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase cancelled by user")
                onPurchaseResult?.invoke(false)
            }
            else -> {
                Log.e(TAG, "Purchase error: ${result.debugMessage}")
                onPurchaseResult?.invoke(false)
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        scope.launch {
            val result = billingClient?.acknowledgePurchase(params)
            if (result?.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.i(TAG, "Purchase acknowledged")
            }
        }
    }

    /**
     * Get formatted price string for display.
     */
    fun getFormattedPrice(): String? {
        return productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
    }

    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
        scope.cancel()
    }
}
