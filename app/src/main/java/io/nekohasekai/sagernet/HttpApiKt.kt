package io.nekohasekai.sagernet

import android.os.Parcel
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ParcelizeBridge
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.forEach
import moe.matsuri.nb4a.utils.Util
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.json.JSONArray
import org.json.JSONObject


class HttpApiKt() {
    companion object {
        @JvmStatic
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

        var gsonParser = JsonParser()
        fun toJson(str: String): JsonObject {
            return gsonParser.parse(str).getAsJsonObject()
        }

        fun toJsonA(str: String): JsonArray {
            return gsonParser.parse(str).asJsonArray
        }

        val DefaultGroupName = "proxy"
    }

    var httpService = HttpService("0.0.0.0", 12123)

    init {
        fun CheckGroup(): Long {
            val group = SagerDatabase.groupDao.allGroups().find {
                it.name == "proxy"
            }
            if (group != null) {
                return group.id
            }
            val newGroup = ProxyGroup()
            newGroup.name = DefaultGroupName
            return SagerDatabase.groupDao.createGroup(newGroup)
        }

        httpService.registerHandler("/upload_setting", HttpService.HttpServerCallback { url, body ->
            HttpApiKt.finishImport(body.getJSONObject("setting"), true, true, true)
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/add_proxy", HttpService.HttpServerCallback { url, body ->
            val response = JSONObject()
            val ids = JSONArray()
            val proxys = body.getJSONArray("proxys")
            val groupId = CheckGroup()
            proxys.forEach { i, item: JSONObject ->
                val proxy = Gson().fromJson(item.toString(), ProxyEntity::class.java)
                proxy.groupId = groupId
                proxy.id = 0
                proxy.userOrder = 0
                val id = SagerDatabase.proxyDao.addProxy(proxy)
                val respItem = JSONObject()
                respItem.put("id", id)
                respItem.put("proxy_name", proxy.displayName())
                ids.put(respItem)
            }
            response.put("proxy_ids", ids);
            return@HttpServerCallback response.toString()
        })

        httpService.registerHandler("/delete_group", HttpService.HttpServerCallback { url, body ->
            SagerDatabase.proxyDao.deleteAll(body.getLong("id"))
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/delete_all", HttpService.HttpServerCallback { url, body ->
            SagerDatabase.proxyDao.reset()
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/get_all", HttpService.HttpServerCallback { url, body ->
            return@HttpServerCallback Gson().toJson(SagerDatabase.proxyDao.getAll())
        })

        httpService.registerHandler("/get_all_group", HttpService.HttpServerCallback { url, body ->
            return@HttpServerCallback Gson().toJson(SagerDatabase.groupDao.allGroups())
        })

        httpService.registerHandler("/start_vpn", HttpService.HttpServerCallback { url, body ->
            DataStore.selectedProxy = body.getLong("id")
            SagerNet.startService()
            return@HttpServerCallback "success"
        })

        httpService.registerHandler("/stop_vpn", HttpService.HttpServerCallback { url, body ->
            SagerNet.stopService()
            return@HttpServerCallback "success"
        })

        try {
            httpService.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

private fun JSONArray.forEach(action: (Int, JSONObject) -> Unit) {
    this.forEach { i: Int, any: Any ->
        action(i, any as JSONObject)
    }
}
