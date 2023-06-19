package io.nekohasekai.sagernet

import android.annotation.SuppressLint
import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Parcel
import android.os.PowerManager
import android.os.StrictMode
import android.os.UserManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.gson.Gson
import go.Seq
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ParcelizeBridge
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.getBool
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.utils.*
import kotlinx.coroutines.DEBUG_PROPERTY_NAME
import kotlinx.coroutines.DEBUG_PROPERTY_VALUE_ON
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import moe.matsuri.nb4a.utils.JavaUtil
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.cleanWebview
import org.json.JSONObject
import java.net.InetSocketAddress
import androidx.work.Configuration as WorkConfiguration

class SagerNet : Application(),
    BoxPlatformInterface,
    WorkConfiguration.Provider, NB4AInterface {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        application = this
    }

    val externalAssets by lazy { getExternalFilesDir(null) ?: filesDir }
    val process = JavaUtil.getProcessName()
    val isMainProcess = process == BuildConfig.APPLICATION_ID
    val isBgProcess = process.endsWith(":bg")
    val httpService = HttpService("127.0.0.1", 12123)

    fun finishImport(content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean) {
        if (profile && content.has("profiles")) {
            val profiles = mutableListOf<ProxyEntity>()
            val jsonProfiles = content.getJSONArray("profiles")
            for (i in 0 until jsonProfiles.length()) {
                val data = Util.b64Decode(jsonProfiles[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                val item = ProxyEntity.CREATOR.createFromParcel(parcel)
                item.groupId = 0
                profiles.add(item)
                parcel.recycle()
            }
            SagerDatabase.proxyDao.reset()
            SagerDatabase.proxyDao.insert(profiles)

            val groups = mutableListOf<ProxyGroup>()
            val jsonGroups = content.getJSONArray("groups")
            for (i in 0 until jsonGroups.length()) {
                val data = Util.b64Decode(jsonGroups[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                groups.add(ProxyGroup.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            SagerDatabase.groupDao.reset()
            SagerDatabase.groupDao.insert(groups)
        }
        if (rule && content.has("rules")) {
            val rules = mutableListOf<RuleEntity>()
            val jsonRules = content.getJSONArray("rules")
            for (i in 0 until jsonRules.length()) {
                val data = Util.b64Decode(jsonRules[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                rules.add(ParcelizeBridge.createRule(parcel))
                parcel.recycle()
            }
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
        if (setting && content.has("settings")) {
            val settings = mutableListOf<KeyValuePair>()
            val jsonSettings = content.getJSONArray("settings")
            for (i in 0 until jsonSettings.length()) {
                val data = Util.b64Decode(jsonSettings[i] as String)
                val parcel = Parcel.obtain()
                parcel.unmarshall(data, 0, data.size)
                parcel.setDataPosition(0)
                settings.add(KeyValuePair.CREATOR.createFromParcel(parcel))
                parcel.recycle()
            }
            PublicDatabase.kvPairDao.reset()
            PublicDatabase.kvPairDao.insert(settings)
        }
    }

    init {
        httpService.registerHandler("/upload_setting", HttpService.HttpServerCallback { url, body ->
            finishImport(JSONObject(body.getString("setting")), true, true, true)
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/add_proxy", HttpService.HttpServerCallback { url, body ->
            val proxy = Gson().fromJson(body.getString("proxy"), ProxyEntity::class.java)
            proxy.groupId = 0
            SagerDatabase.proxyDao.addProxy(proxy)
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/delete_all", HttpService.HttpServerCallback { url, body ->
            SagerDatabase.proxyDao.deleteAll(0)
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/get_all", HttpService.HttpServerCallback { url, body ->
            return@HttpServerCallback Gson().toJson(SagerDatabase.proxyDao.getAll())
        })

        httpService.registerHandler("/start_vpn", HttpService.HttpServerCallback { url, body ->
            DataStore.selectedProxy = body.getLong("id")
            reloadService()
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/stop_vpn", HttpService.HttpServerCallback { url, body ->
            stopService()
            return@HttpServerCallback "success"
        })
    }

    override fun onCreate() {
        super.onCreate()

        System.setProperty(DEBUG_PROPERTY_NAME, DEBUG_PROPERTY_VALUE_ON)
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler)

        if (isMainProcess || isBgProcess) {
            // fix multi process issue in Android 9+
            JavaUtil.handleWebviewDir(this)

            runOnDefaultDispatcher {
                PackageCache.register()
                cleanWebview()
            }
        }

        Seq.setContext(this)
        updateNotificationChannels()

        // nb4a: init core
        externalAssets.mkdirs()
        Libcore.initCore(
            process,
            cacheDir.absolutePath + "/",
            filesDir.absolutePath + "/",
            externalAssets.absolutePath + "/",
            DataStore.logBufSize,
            DataStore.logLevel > 0,
            this
        )

        // libbox: platform interface
        Libcore.setBoxPlatformInterface(this)

        if (isMainProcess) {
            Theme.apply(this)
            Theme.applyNightTheme()
            runOnDefaultDispatcher {
                DefaultNetworkListener.start(this) {
                    underlyingNetwork = it
                }
            }
        }

        if (BuildConfig.DEBUG) StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build()
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateNotificationChannels()
    }

    override fun getWorkManagerConfiguration(): WorkConfiguration {
        return WorkConfiguration.Builder()
            .setDefaultProcessName("${BuildConfig.APPLICATION_ID}:bg")
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        Libcore.forceGc()
    }

    @SuppressLint("InlinedApi")
    companion object {

        lateinit var application: SagerNet

        val isTv by lazy {
            uiMode.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        }

        // /data/user_de available when not unlocked
        val deviceStorage by lazy {
            if (Build.VERSION.SDK_INT < 24) application else DeviceStorageApp(application)
        }

        val configureIntent: (Context) -> PendingIntent by lazy {
            {
                PendingIntent.getActivity(
                    it,
                    0,
                    Intent(
                        application, MainActivity::class.java
                    ).setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                )
            }
        }
        val activity by lazy { application.getSystemService<ActivityManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val user by lazy { application.getSystemService<UserManager>()!! }
        val uiMode by lazy { application.getSystemService<UiModeManager>()!! }
        val power by lazy { application.getSystemService<PowerManager>()!! }

        fun getClipboardText(): String {
            return clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString() ?: ""
        }

        fun trySetPrimaryClip(clip: String) = try {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, clip))
            true
        } catch (e: RuntimeException) {
            Logs.w(e)
            false
        }

        fun updateNotificationChannels() {
            if (Build.VERSION.SDK_INT >= 26) @RequiresApi(26) {
                notification.createNotificationChannels(
                    listOf(
                        NotificationChannel(
                            "service-vpn",
                            application.getText(R.string.service_vpn),
                            if (Build.VERSION.SDK_INT >= 28) NotificationManager.IMPORTANCE_MIN
                            else NotificationManager.IMPORTANCE_LOW
                        ),   // #1355
                        NotificationChannel(
                            "service-proxy",
                            application.getText(R.string.service_proxy),
                            NotificationManager.IMPORTANCE_LOW
                        ), NotificationChannel(
                            "service-subscription",
                            application.getText(R.string.service_subscription),
                            NotificationManager.IMPORTANCE_DEFAULT
                        )
                    )
                )
            }
        }

        fun startService() = ContextCompat.startForegroundService(
            application, Intent(application, SagerConnection.serviceClass)
        )

        fun reloadService() =
            application.sendBroadcast(Intent(Action.RELOAD).setPackage(application.packageName))

        fun stopService() =
            application.sendBroadcast(Intent(Action.CLOSE).setPackage(application.packageName))

        var underlyingNetwork: Network? = null

    }


    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
