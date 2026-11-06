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

/*
 * GroupInfoFragment's group-encryption flows: the encryption sheets, upgrade to encrypted, and the
 * interim reset-crypto dialog with its key rotation and default-avatar generation. Moved out of the
 * fragment unchanged; they read its group state and injected repositories.
 */

// Server status for a rotate-crypto CAS conflict (baseGroupCryptoKeyVersion stale).
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
    // Reset entry gated to owner/admin, behind the group-encryption flag AND
    // its own independent reset flag (hidden when reset flag is off).
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
                // Prefill from the operator's view: WITH key → decrypted current name +
                // current avatar. WITHOUT key (can't decrypt the placeholders) → empty
                // name + a freshly generated default avatar, staged so it's both shown
                // in the dialog and uploaded on confirm.
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

/**
 * Interim reset-crypto dialog — replace with the proper design-driven form
 * when the interaction design lands. A centered [ComposeDialogManager]
 * message dialog with an editable avatar (staged pick) + a single name field
 * (prefilled with the currently decrypted group name). cancelable=false so an
 * accidental outside-tap can't dismiss a destructive key-rotation entry mid-edit,
 * and so the dialog survives the picker round-trip.
 */
// TODO interim UI — replace with proper form when design lands
internal fun GroupInfoFragment.showResetCryptoDialog(prefillName: String) {
    // Hoist the name state to the page so the imperative onConfirm can read
    // the final value (the @Composable content slot re-runs on each keystroke,
    // so we must not own the state inside it). remember{} keeps the same
    // instance across recompositions of the content slot.
    // prefillName is the decrypted name when the operator holds the key, else ""
    // (no-key case shows an empty field). resetAvatarPath is pre-set by the caller
    // (null when key is held → preview shows current avatar; a generated path
    // otherwise → preview shows the random default).
    val nameState = mutableStateOf(prefillName)
    var dialog: ComposeDialog? = null
    dialog = dismissWithView(ComposeDialogManager.showMessageDialog(
        context = requireActivity(),
        title = getString(R.string.group_crypto_reset_title),
        confirmText = getString(R.string.group_crypto_reset_confirm),
        cancelText = getString(R.string.chat_dialog_cancel),
        showCancel = true,
        cancelable = false,
        // Validate before closing: a blank name must keep the dialog open (and preserve the
        // staged avatar pick), so disable auto-dismiss and dismiss manually on valid confirm.
        autoDismiss = false,
        content = { ResetCryptoDialogContent(nameState) },
        onConfirm = {
            // Trim leading/trailing spaces, then block submit on an empty name (keep the
            // dialog open); otherwise close and rotate with the trimmed name.
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

/**
 * Editable content slot for [showResetCryptoDialog]: body line + staged-pick avatar
 * (tap → picker) + a single name field. [nameState] is hoisted to the Activity so the
 * imperative onConfirm can read the final value; remember{} keeps the same instance
 * across recompositions of this slot.
 */
@Composable
internal fun GroupInfoFragment.ResetCryptoDialogContent(nameState: MutableState<String>) {
    val rememberedState = remember { nameState }
    // Observe the staged pick so the avatar recomposes after a pick lands.
    val pickedPath by resetAvatarPath
    Column(modifier = Modifier.fillMaxWidth()) {
        // Body (above the editable fields): what reset does.
        Text(
            text = stringResource(R.string.group_crypto_reset_message),
            style = DifftTheme.typography.p3,
            color = DifftTheme.colors.textSecondary
        )
        Spacer(modifier = Modifier.height(16.dp))
        // Editable group avatar: shows the staged pick when present, else the
        // current group avatar (default group icon when data is null). Tapping
        // launches the picker; the staged path is uploaded+encrypted at confirm
        // time. cancelable=false keeps the dialog alive across the picker round-trip.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.clickable {
                    // Omit context → uses application default, avoiding capture of the
                    // Activity context inside this composable clickable.
                    // Open directly when media is already usable (full/partial); else request.
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
                // Camera overlay (bottom-end): 24dp circle + centered 12dp camera icon, same
                // badge the create-group screen uses. The bg is a <shape> oval (icon fill +
                // bg_popup stroke), which Compose painterResource can't load — recreate it with
                // clip/background/border.
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
        // maxLength truncates on paste — InputFilter.LengthFilter semantics.
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

/**
 * Plaintext avatar JSON to preserve when no new avatar is picked: decrypt the current
 * [GroupModel.encryptedAvatar] with the stored K_group, so it can be re-encrypted under
 * the fresh key. Returns null when there's no stored key or no encrypted avatar (caller
 * then generates a default). Deliberately does NOT read [GroupModel.avatar]: that field
 * holds the decrypted JSON only when the model came through decryptGroupFieldsIfNeeded,
 * which getSingleGroupInfo(forceUpdate=false) skips — so for an encrypted group it can be
 * the blank/un-decrypted cached value. Must be called from an IO thread.
 */
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
    // Snapshot the staged pick now so a late dialog dismissal can't race the build.
    val pickedAvatarPath = resetAvatarPath.value
    val gid = groupId
    // Page inputs first: nothing that can throw may sit between the claim and its release.
    val appContext = requireContext().applicationContext
    val defaultName = getString(R.string.new_group)

    // Process-wide claim (a rotation started by a page a fold destroyed still blocks this one;
    // the page shows the wait state from the registry). Runs to completion across a fold
    // recreation: a rotation the server accepted must be applied locally even if this page was
    // destroyed meanwhile.
    val started = GroupCryptoOperations.launch(this, gid) {
        try {
            // Resolve the new avatar's plaintext JSON ONCE — it doesn't change between CAS
            // attempts, so the upload happens a single time and a CAS retry never re-uploads;
            // each attempt only re-encrypts this JSON with its own fresh K_group. Resolved
            // INSIDE try so an upload/decrypt failure still hits the catch and the finally
            // (ends the operation) — otherwise the wait state would stick and rotation would be
            // permanently blocked.
            //  - picked file → upload + build JSON
            //  - else preserve the current avatar → decrypt encryptedAvatar with the stored key
            //  - else (all-lost / no custom avatar) → generate + upload a default
            val plaintextAvatarJson = withContext(Dispatchers.IO) {
                when {
                    pickedAvatarPath != null -> groupAvatarUploader.uploadAndBuildJson(pickedAvatarPath)
                    else -> preserveCurrentAvatarJson(group)
                        ?: groupAvatarUploader.uploadAndBuildJson(generateDefaultAvatarFile(group.members ?: emptyList(), appContext))
                }
            }
            // One rotate attempt: force-fetch the latest group info, build the request against
            // that fresh CAS base + current members, and call rotate-crypto. The group-info
            // page can hold a stale cached groupCryptoKeyVersion (getSingleGroupInfo
            // forceUpdate=false returns the DB cache, which may lag the server), so we always
            // refetch — the CAS base must be the server's current version, per contract. On a
            // fetch dedup (a concurrent in-flight fetch returns null) fall back to the freshest
            // PERSISTED group (getSingleGroupInfo reads the cache the concurrent fetch just
            // wrote), not the stale in-memory one. Returns (response, freshRGroup).
            suspend fun attemptRotate() = withContext(Dispatchers.IO) {
                val freshGroup = groupUtil.fetchAndSaveSingleGroupInfo(groupId)
                    ?: groupUtil.getSingleGroupInfo(groupId)
                    ?: group
                val (request, rGroup) = buildRotateRequest(freshGroup, newName, defaultName, plaintextAvatarJson)
                Triple(groupRepo.rotateCrypto(groupId, request), rGroup, request.baseGroupCryptoKeyVersion)
            }
            var result = attemptRotate()
            // CAS conflict: another rotation beat our base between the fetch and the
            // request. Re-fetch + retry exactly once (fresh base); don't loop, to avoid
            // a rotation storm.
            if (result.first.status == STATUS_GROUP_CRYPTO_VERSION_CONFLICT) {
                L.w { "[GE] rotate version conflict gid=$groupId, retrying once with fresh base" }
                result = attemptRotate()
            }
            val (response, newRGroup, requestBase) = result

            if (response.status == 0) {
                // setRotatedRGroup writes an equal or newer version and ignores an older one
                // (a later rotation's key arrived meanwhile); a dropped write distributes nothing.
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
                    // Server rotated successfully but the local apply failed: the group
                    // is now rotated server-side while our key state is stranded. Log
                    // distinctly so the partial state is observable; the generic catch
                    // below would mask it as a plain net error.
                    L.e { "[GE] rotate succeeded server-side but local apply failed gid=$groupId: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.showLong(R.string.group_crypto_reset_failed) }
                }
                // Uploaded to the server — drop the plaintext compress/crop temp of the pick
                // (no-op for the current-avatar or generated-default paths).
                AvatarPickTempCleaner.deleteUploadedTemp(appContext, pickedAvatarPath)
            } else {
                // Already refetched + retried once above for a CAS conflict; if still
                // failing, just surface the error (attemptRotate refetches a fresh base
                // on the next manual attempt too).
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

/**
 * Version of a successful rotation: the server's, else the CAS base the request was built on + 1
 * (a successful CAS moves exactly one step). Never derived from the stored row at apply time, which
 * a newer key received meanwhile may already have advanced.
 */
internal fun rotatedKeyVersion(serverVersion: Int?, requestBase: Int): Int = serverVersion ?: (requestBase + 1)

/** Local outcome of a rotation the server accepted. */
internal enum class InitiatorRotation {
    /** Our key was stored, distributed and the group refreshed. */
    APPLIED,

    /** A newer key from a later rotation was stored meanwhile; ours is dropped, nothing distributed. */
    SUPERSEDED,
}

/**
 * The rotation initiator's local apply: store the new key, and only if it was stored (not
 * superseded by a newer key received meanwhile) distribute it and refresh the group.
 */
internal suspend fun applyInitiatorRotation(setRotated: () -> Boolean, distribute: suspend () -> Unit): InitiatorRotation {
    if (!setRotated()) return InitiatorRotation.SUPERSEDED
    distribute()
    return InitiatorRotation.APPLIED
}

/**
 * Both outcomes mean the reset the user asked for happened on the server: a superseding key can
 * only come from a rotation built on ours, so the group key was reset either way.
 */
@StringRes
internal fun rotationResetMessage(outcome: InitiatorRotation): Int = when (outcome) {
    InitiatorRotation.APPLIED, InitiatorRotation.SUPERSEDED -> R.string.group_crypto_reset_success
}

/**
 * Build the rotate-crypto request: a fresh R_group, new derived keys, the form
 * name re-encrypted, and all current members re-signed. Modeled on
 * [buildUpgradeRequest] — the originator never reads the old key, so this also
 * works when the group is in the all-members-lost state.
 *
 * The avatar's plaintext JSON is resolved by the caller ONCE (uploaded a single time)
 * and passed in, so a CAS retry only re-encrypts it with the new K_group rather than
 * re-uploading. This method just encrypts it; the rotate contract requires
 * encryptedAvatar to be present, so the caller always supplies a non-null JSON.
 *
 * Must be called from IO thread.
 * @param plaintextAvatarJson the avatar JSON to encrypt with the fresh K_group.
 * @return Pair of (request, newRGroup) — newRGroup kept coroutine-local.
 */
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
        // CAS base: the current version we last saw from the server. un-rotated default 0.
        // TODO 联调: confirm server's un-rotated groupCryptoKeyVersion value
        baseGroupCryptoKeyVersion = group.groupCryptoKeyVersion ?: 0,
        memberBindings = memberBindings
    )
    return request to newRGroup
}

/**
 * Generate a default group letter avatar PNG from the current members and return
 * its local file path. Mirrors [CreateGroupActivity.generateAvatar]'s mechanism
 * (LetterItems from member display names, color keyed by member id).
 * Must be called from IO thread.
 */
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
        ?: context.getColor(com.difft.android.base.R.color.primary)

    return GroupAvatarUtil.generateAvatarFile(letterItems, backgroundColor)
        ?: throw java.io.IOException("default_avatar_generate_failed")
}

internal fun GroupInfoFragment.performUpgradeToEncrypted() {
    val group = groupInfo ?: return
    val gid = groupId

    // Runs to completion across a fold recreation: an upgrade the server accepted must store and
    // distribute the new key even if this page was destroyed meanwhile.
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

/**
 * Build the upgrade request with encrypted fields and member bindings.
 * Must be called from IO thread.
 * @return Pair of (request, rGroup) — rGroup kept as coroutine-local to avoid Activity state loss.
 */
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
