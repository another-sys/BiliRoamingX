package app.revanced.bilibili.patches.protobuf.hooks

import app.revanced.bilibili.patches.protobuf.MossHook
import app.revanced.bilibili.settings.Settings
import app.revanced.bilibili.utils.ArrayUtils
import com.bapis.bilibili.ad.v1.SourceContentDto
import com.bapis.bilibili.app.dynamic.v2.*
import com.google.protobuf.GeneratedMessageLite
import com.google.protobuf.InvalidProtocolBufferException

abstract class DynListBase<out Req : GeneratedMessageLite<*, *>, out Resp : GeneratedMessageLite<*, *>> :
    MossHook<Req, Resp>() {
    companion object {
        private var cachedContentSet = setOf<String>()
        private var cachedContentRegexes = listOf<Regex>()
    }

    protected fun List<DynamicItem>.getToRemoveIdxList(): List<Int> {
        val contentSet = Settings.DynPurifyContent()
        val contentRegexMode = Settings.DynPurifyContentRegexMode()
        val contentRegexes = if (contentRegexMode && cachedContentSet == contentSet) {
            cachedContentRegexes
        } else if (contentRegexMode) {
            cachedContentSet = HashSet(contentSet)
            contentSet.map { it.toRegex() }.also { cachedContentRegexes = it }
        } else listOf()
        val typeSet = Settings.DynPurifyType()
        val ups = Settings.DynPurifyUp()
        val uidSet = Settings.DynPurifyUid()
        val topics = Settings.DynPurifyTopic()
        val rmBlocked = Settings.DynRmBlocked()
        val rmAdLink = Settings.DynRmAdLink()
        val rmUpReservation = Settings.DynRmUpReservation()
        val rmCm = Settings.DynRmCm()
        val rmStory = Settings.DynRmStory()
        val typeArray = ArrayUtils.toIntArray(typeSet)
        val uidArray = ArrayUtils.toLongArray(uidSet)
        val idxList = mutableListOf<Int>()
        for ((idx, item) in withIndex()) {
            val cardType = item.cardTypeValue
            if (typeArray.isNotEmpty() && typeArray.contains(cardType)) {
                idxList.add(idx)
                continue
            }
            val extend = item.extend
            if (rmBlocked && extend.hasOnlyFansProperty()
                && !extend.onlyFansProperty.hasPrivilege
            ) {
                idxList.add(idx)
                continue
            }
            if (rmCm && item.withForward().any { it.isPromotedCard() }) {
                idxList.add(idx)
                continue
            }
            if ((rmAdLink || rmUpReservation) && item.withForward().any { i ->
                    (rmAdLink && (i.extend.origDescList.any { it.type == DescType.desc_type_goods }
                            || i.extend.descList.any { it.type == DescType.desc_type_goods }))
                            || i.modulesList.any { module ->
                        module.hasModuleAdditional() && when (module.moduleAdditional.type) {
                            AdditionalType.additional_type_goods, AdditionalType.additional_type_up_rcmd -> rmAdLink
                            AdditionalType.additional_type_up_reservation -> rmUpReservation
                            else -> false
                        }
                    }
                }) {
                idxList.add(idx)
                continue
            }
            if (rmStory && item.cardType == DynamicType.av && item.withForward().any { i ->
                    i.modulesList.find { it.hasModuleDynamic() }?.moduleDynamic?.dynArchive
                        ?.stype == VideoType.video_type_story
                }) {
                idxList.add(idx)
                continue
            }
            if (contentSet.isNotEmpty()) {
                val text = item.modulesList.firstNotNullOfOrNull {
                    if (it.hasModuleDesc()) it.moduleDesc.text else null
                }
                if (text.shouldBlock(contentRegexMode, contentSet, contentRegexes)) {
                    idxList.add(idx)
                    continue
                }
                val orig = extend.origDescList.joinToString(separator = "") { it.origText }
                if (orig.shouldBlock(contentRegexMode, contentSet, contentRegexes)) {
                    idxList.add(idx)
                    continue
                }
                val desc = extend.descList.joinToString(separator = "") { it.origText }
                if (desc.shouldBlock(contentRegexMode, contentSet, contentRegexes)) {
                    idxList.add(idx)
                    continue
                }
            }
            if (topics.isNotEmpty()) {
                val topic = item.modulesList.firstNotNullOfOrNull {
                    if (it.hasModuleTopic()) it.moduleTopic.name else null
                }
                if (!topic.isNullOrEmpty() && topics.any { topic.contains(it) }) {
                    idxList.add(idx)
                    continue
                }
            }
            if (ups.isNotEmpty()) {
                val origName = extend.origName
                if (!origName.isNullOrEmpty() && ups.any { origName == it }) {
                    idxList.add(idx)
                    continue
                }
            }
            if (uidArray.isNotEmpty()) {
                val uid = extend.uid
                if (uid > 0 && uidArray.contains(uid)) {
                    idxList.add(idx)
                    continue
                }
            }
        }
        return idxList
    }

    private fun DynamicItem.isPromotedCard(): Boolean {
        if (cardType == DynamicType.ad) return true
        if (!extend.hasSourceContent()) return false
        val source = extend.sourceContent
        if (source.typeUrl.substringAfterLast('/') != "bilibili.ad.v1.SourceContentDto") return false
        return try {
            SourceContentDto.parseFrom(source.value).isAdLoc
        } catch (_: InvalidProtocolBufferException) {
            false
        }
    }

    private fun DynamicItem.withForward(): Array<DynamicItem> {
        val forwardItem = modulesList.find { it.hasModuleDynamic() }?.moduleDynamic
            ?.takeIf { it.hasDynForward() }?.dynForward?.item
        return if (forwardItem == null) arrayOf(this) else arrayOf(this, forwardItem)
    }

    private fun String?.shouldBlock(
        regexMode: Boolean,
        contents: Set<String>,
        regexes: List<Regex>
    ) = if (regexMode && !isNullOrEmpty())
        regexes.any { contains(it) }
    else if (!isNullOrEmpty())
        contents.any { contains(it) }
    else false
}
