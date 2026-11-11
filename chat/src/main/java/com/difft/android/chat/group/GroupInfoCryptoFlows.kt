package com.difft.android.chat.group

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import androidx.annotation.StringRes
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.compose.input.ClearMode
import com.difft.android.base.ui.compose.input.DifftClearableTextField
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.common.GroupAvatarView
import com.difft.android.chat.common.LetterItem
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.network.group.GroupMemberBinding
import com.difft.android.network.group.RotateGroupCryptoReq
import com.difft.android.network.group.UpgradeGroupToEncryptedReq
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.members
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.models.GroupModel
import kotlin.coroutines.cancellation.CancellationException




private const val STATUS_GROUP_CRYPTO_VERSION_CONFLICT = 29

internal fun GroupInfoFragment.showUpgradeToEncryptedSheet() {
    var dialog: ComposeDialog? = null
    dialog = dismissWithView(ComposeDialogManager.showBottomDialog(requireActivity()) {
        GroupEncryptionBottomSheet(
            isUpgrade = true,
            onUpgrade = {
                dialog?.dismiss()
                performUpgradeToEncrypted()
            },
            onDismiss = { dialog?.dismiss() }
        )
    })
}

internal fun GroupInfoFragment.showEncryptedGroupInfoSheet() {
    
    
    val canReset = role <= GROUP_ROLE_ADMIN &&
            globalConfigsManager.isGroupEncryptionEnabled() &&
            globalConfigsManager.isGroupEncryptionKeyResetEnabled()
    var dialog: ComposeDialog? = null
    dialog = dismissWithView(ComposeDialogManager.showBottomDialog(requireActivity()) {
        GroupEncryptionBottomSheet(
            isUpgrade = false,
            onUpgrade = { },
            onDismiss = { dialog?.dismiss() },
            canReset = canReset,
            onReset = {
                
                
                
                
                viewScope.launch {
                    val hasKey = withContext(Dispatchers.IO) { groupCryptoRepo.hasKeys(groupId) }
                    resetAvatarPath.value = if (hasKey) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                generateDefaultAvatarFile(groupInfo?.members ?: emptyList(), requireContext())
                            }.getOrNull()
                        }
                    }
                    showResetCryptoDialog(prefillName = if (hasKey) groupInfo?.name ?: "" else "")
                }
            }
        )
    })
}




internal fun GroupInfoFragment.showResetCryptoDialog(prefillName: String) {
    
    
    
    
    
    
    
    
    val nameState = mutableStateOf(prefillName)
    var dialog: ComposeDialog? = null
    dialog = dismissWithView(ComposeDialogManager.showMessageDialog(
        context = requireActivity(),
        title = getString(R.string.group_crypto_reset_title),
        confirmText = getString(R.string.group_crypto_reset_confirm),
        cancelText = getString(R.string.chat_dialog_cancel),
        showCancel = true,
        cancelable = false,
        
        
        autoDismiss = false,
        content = { ResetCryptoDialogContent(nameState) },
        onConfirm = {
            
            
            val name = nameState.value.trim()
            if (name.isBlank()) {
                ToastUtil.showLong(R.string.group_crypto_reset_name_hint)
            } else {
                dialog?.dismiss()
                performResetCrypto(name)
            }
        },
        onCancel = { dialog?.dismiss() }
    ))
}







@Composable
internal fun GroupInfoFragment.ResetCryptoDialogContent(nameState: MutableState<String>) {
    val rememberedState = remember { nameState }
    
    val pickedPath by resetAvatarPath
    Column(modifier = Modifier.fillMaxWidth()) {
        
        Text(
            text = stringResource(R.string.group_crypto_reset_message),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        
        
        
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable {
                    
                    
                    
                    resetAvatarPicker.launch()
                }
            ) {
                AndroidView(
                    factory = { ctx -> GroupAvatarView(ctx) },
                    update = { v ->
                        val p = pickedPath
                        if (p != null) {
                            v.setAvatar(p)
                        } else {
                            v.setAvatar(groupInfo?.getDisplayAvatarData(), gid = groupId)
                        }
                    },
                    modifier = Modifier.size(64.dp)
                )
                
                
                
                
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(DifftTheme.colors.icon)
                        .border(2.dp, DifftTheme.colors.backgroundPopup, CircleShape)
                ) {
                    Image(
                        painter = painterResource(R.drawable.chat_contact_camera),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(DifftTheme.colors.backgroundPopup),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        
        DifftClearableTextField(
            value = rememberedState.value,
            onValueChange = { rememberedState.value = it },
            onClear = { rememberedState.value = "" },
            hint = stringResource(R.string.group_crypto_reset_name_hint),
            clearMode = ClearMode.WhileEditing,
            containerColor = DifftTheme.colors.backgroundTertiary,
            textStyle = DifftTheme.typography.bodyMedium.copy(fontSize = 18.sp),
            height = null,
            minHeight = 36.dp,
            contentPadding = PaddingValues(start = 12.dp),
            maxLength = 64,
            modifier = Modifier.fillMaxWidth()
        )
    }
}










internal fun GroupInfoFragment.preserveCurrentAvatarJson(group: GroupModel): String? {
    val encryptedAvatar = group.encryptedAvatar ?: return null
    val rGroupBytes = groupCryptoRepo.getRGroupBytes(groupId) ?: return null
    return try {
        GroupCrypto.decryptGroupAvatar(GroupCrypto.deriveKGroup(rGroupBytes), encryptedAvatar)
    } catch (e: Exception) {
        L.w { "[GE] preserve avatar decrypt failed gid=$groupId: ${e.message}" }
        null
    }
}

internal fun GroupInfoFragment.performResetCrypto(newName: String) {
    val group = groupInfo ?: return
    
    val pickedAvatarPath = resetAvatarPath.value
    val gid = groupId
    
    val appContext = requireContext().applicationContext
    val defaultName = getString(R.string.new_group)

    
    
    
    
    val started = GroupCryptoOperations.launch(this, gid) {
        try {
            
            
            
            
            
            
            
            
            
            val plaintextAvatarJson = withContext(Dispatchers.IO) {
                when {
                    pickedAvatarPath != null -> groupAvatarUploader.uploadAndBuildJson(pickedAvatarPath)
                    else -> preserveCurrentAvatarJson(group)
                        ?: groupAvatarUploader.uploadAndBuildJson(generateDefaultAvatarFile(group.members ?: emptyList(), appContext))
                }
            }
            
            
            
            
            
            
            
            
            suspend fun attemptRotate() = withContext(Dispatchers.IO) {
                val freshGroup = groupUtil.fetchAndSaveSingleGroupInfo(groupId)
                    ?: groupUtil.getSingleGroupInfo(groupId)
                    ?: group
                val (request, rGroup) = buildRotateRequest(freshGroup, newName, defaultName, plaintextAvatarJson)
                Triple(groupRepo.rotateCrypto(groupId, request), rGroup, request.baseGroupCryptoKeyVersion)
            }
            var result = attemptRotate()
            
            
            
            if (result.first.status == STATUS_GROUP_CRYPTO_VERSION_CONFLICT) {
                L.w { "[GE] rotate version conflict gid=$groupId, retrying once with fresh base" }
                result = attemptRotate()
            }
            val (response, newRGroup, requestBase) = result

            if (response.status == 0) {
                
                
                try {
                    val outcome = withContext(Dispatchers.IO) {
                        val newVersion = rotatedKeyVersion(response.data?.groupCryptoKeyVersion, requestBase)
                        applyInitiatorRotation(
                            setRotated = { groupCryptoRepo.setRotatedRGroup(groupId, newRGroup, newVersion) },
                            distribute = {
                                groupKeyDistributor.distributeToGroup(groupId)
                                groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                            },
                        ).also {
                            L.i {
                                "[GE] Reset crypto key for group $groupId v=$newVersion " +
                                    "superseded=${it == InitiatorRotation.SUPERSEDED}"
                            }
                        }
                    }
                    onPage { ToastUtil.show(rotationResetMessage(outcome)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    
                    
                    
                    
                    L.e { "[GE] rotate succeeded server-side but local apply failed gid=$groupId: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.showLong(R.string.group_crypto_reset_failed) }
                }
                
                
                AvatarPickTempCleaner.deleteUploadedTemp(appContext, pickedAvatarPath)
            } else {
                
                
                
                L.w { "[GE] Reset crypto for group $groupId failed status=${response.status}: ${response.reason}" }
                val reason = response.reason
                onPage {
                    if (!reason.isNullOrEmpty()) {
                        ToastUtil.showLong(reason)
                    } else {
                        ToastUtil.showLong(R.string.group_crypto_reset_failed)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupInfo] resetCrypto error: ${e.stackTraceToString()}" }
            onPage { ToastUtil.showLong(R.string.group_crypto_reset_failed) }
        }
    }
    if (!started) L.i { "[GE] resetCrypto already in progress gid=$gid, ignoring re-entry" }
}






internal fun rotatedKeyVersion(serverVersion: Int?, requestBase: Int): Int = serverVersion ?: (requestBase + 1)


internal enum class InitiatorRotation {
    
    APPLIED,

    
    SUPERSEDED,
}





internal suspend fun applyInitiatorRotation(setRotated: () -> Boolean, distribute: suspend () -> Unit): InitiatorRotation {
    if (!setRotated()) return InitiatorRotation.SUPERSEDED
    distribute()
    return InitiatorRotation.APPLIED
}





@StringRes
internal fun rotationResetMessage(outcome: InitiatorRotation): Int = when (outcome) {
    InitiatorRotation.APPLIED, InitiatorRotation.SUPERSEDED -> R.string.group_crypto_reset_success
}
















internal fun buildRotateRequest(
    group: GroupModel,
    newName: String,
    defaultName: String,
    plaintextAvatarJson: String
): Pair<RotateGroupCryptoReq, ByteArray> {
    val newRGroup = GroupCrypto.generateRGroup()
    val kGroup = GroupCrypto.deriveKGroup(newRGroup)
    val skBind = GroupCrypto.deriveSkBind(newRGroup)
    val pkBind = GroupCrypto.derivePkBind(newRGroup)

    val name = newName.ifBlank { defaultName }
    val encryptedName = GroupCrypto.encryptGroupName(kGroup, name)

    val members = group.members ?: emptyList()
    val encryptedAvatar = GroupCrypto.encryptGroupAvatar(kGroup, plaintextAvatarJson)

    val memberBindings = members.mapNotNull { member ->
        member.id?.let { uid ->
            GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid))
        }
    }
    val pkBindBase64 = GroupCrypto.pkBindToSpkiBase64(pkBind)

    val request = RotateGroupCryptoReq(
        encryptedName = encryptedName,
        encryptedAvatar = encryptedAvatar,
        groupMemberVerifyPublicKey = pkBindBase64,
        
        
        baseGroupCryptoKeyVersion = group.groupCryptoKeyVersion ?: 0,
        memberBindings = memberBindings
    )
    return request to newRGroup
}







internal fun generateDefaultAvatarFile(members: List<GroupMemberContactorModel>, context: Context): String {
    val letterItems = members
        .filter { !it.id.isNullOrEmpty() && !it.displayName.isNullOrEmpty() }
        .take(6)
        .map { member ->
            val letter = ContactorUtil.getFirstLetter(member.displayName).first()
            val color = AvatarUtil.getBgColorResId(member.id!!)
            LetterItem(letter.uppercaseChar(), color)
        }
    val usedColors = letterItems.map { it.color }.toSet()
    val availableColors = AvatarUtil.colors.filterNot { usedColors.contains(it) }
    val backgroundColor = availableColors.randomOrNull()
        ?: context.getColor(com.difft.android.base.R.color.info)

    return GroupAvatarUtil.generateAvatarFile(letterItems, backgroundColor)
        ?: throw java.io.IOException("default_avatar_generate_failed")
}

internal fun GroupInfoFragment.performUpgradeToEncrypted() {
    val group = groupInfo ?: return
    val gid = groupId

    
    
    val started = GroupCryptoOperations.launch(this, gid) {
        try {
            val (request, rGroup) = withContext(Dispatchers.IO) {
                buildUpgradeRequest(group)
            }
            val response = withContext(Dispatchers.IO) {
                groupRepo.upgradeToEncrypted(groupId, request)
            }

            if (response.status == 0) {
                withContext(Dispatchers.IO) {
                    groupCryptoRepo.saveRGroupIfNeeded(groupId, rGroup)
                    groupKeyDistributor.distributeToGroup(groupId)
                    groupUtil.fetchAndSaveSingleGroupInfo(groupId, true)
                }
                L.i { "[GE] Upgraded group $groupId to encrypted" }
                onPage { ToastUtil.show(R.string.operation_successful) }
            } else {
                L.w { "[GE] Upgrade group $groupId failed: ${response.reason}" }
                response.reason?.let { reason -> onPage { ToastUtil.showLong(reason) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.w { "[GroupInfo] upgradeToEncrypted error: ${e.stackTraceToString()}" }
            onPage { ToastUtil.showLong(R.string.chat_net_error) }
        }
    }
    if (!started) L.i { "[GE] upgrade already in progress gid=$gid, ignoring re-entry" }
}






internal fun GroupInfoFragment.buildUpgradeRequest(group: GroupModel): Pair<UpgradeGroupToEncryptedReq, ByteArray> {
    val rGroup = GroupCrypto.generateRGroup()
    val kGroup = GroupCrypto.deriveKGroup(rGroup)
    val skBind = GroupCrypto.deriveSkBind(rGroup)
    val pkBind = GroupCrypto.derivePkBind(rGroup)

    val encryptedName = GroupCrypto.encryptGroupName(kGroup, group.name ?: "")
    val encryptedAvatar = group.avatar?.let { GroupCrypto.encryptGroupAvatar(kGroup, it) }

    val members = group.members ?: emptyList()
    val memberBindings = members.mapNotNull { member ->
        member.id?.let { uid ->
            GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid))
        }
    }
    val pkBindBase64 = GroupCrypto.pkBindToSpkiBase64(pkBind)

    val request = UpgradeGroupToEncryptedReq(
        encryptedName = encryptedName,
        encryptedAvatar = encryptedAvatar,
        groupMemberVerifyPublicKey = pkBindBase64,
        memberBindings = memberBindings
    )
    return request to rGroup
}
