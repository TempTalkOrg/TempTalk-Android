package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import com.difft.android.call.R
import com.twilio.audioswitch.AudioDevice

@Composable
fun ShowAudioDeviceOnClickView(
    modifier: Modifier = Modifier,
    audioDevices: List<AudioDevice>,
    currentDevice: AudioDevice?,
    expanded: Boolean,
    setExpanded:(Boolean) ->Unit,
    onClickItem: (AudioDevice) -> Unit
){

    val context = LocalContext.current

    DropdownMenu(
        modifier = Modifier
            .clip(shape = RoundedCornerShape(12.dp))
            .background(colorResource(id = com.difft.android.base.R.color.bg_popup_night)),
        expanded = expanded,
        onDismissRequest = { setExpanded(false) },
    ) {
        audioDevices.forEachIndexed { index, item ->
            DropdownMenuItem(
                modifier = Modifier
                    .size(width = 247.dp, height = 30.dp)
                    .background(
                        colorResource(id = com.difft.android.base.R.color.bg_popup_night)
                    ),
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ){
                        ConstraintLayout(
                            modifier = Modifier.fillMaxWidth()
                        ){
                            val (deviceIcon, deviceName) = createRefs()

                            val shareIconPainter= painterResource(
                                id = when (item) {
                                    is AudioDevice.Earpiece -> R.drawable.tabler_device_phone
                                    is AudioDevice.Speakerphone -> R.drawable.tabler_device_speaker
                                    is AudioDevice.WiredHeadset -> R.drawable.tabler_device_headphones
                                    is AudioDevice.BluetoothHeadset -> R.drawable.tabler_device_airpods
                                    else -> R.drawable.tabler_device_speaker
                                }
                            )

                            Image(
                                painter = shareIconPainter,
                                contentDescription = "audio device icon",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.constrainAs(deviceIcon){
                                    start.linkTo(parent.start, margin = 8.dp)
                                    centerVerticallyTo(parent)
                                }
                            )

                            val itemName = if(currentDevice!=null && currentDevice == item){
                                context.getString(R.string.call_current_audio_device).format(item.name)
                            }else {
                                item.name
                            }

                            Text(
                                modifier = Modifier.constrainAs(deviceName){
                                    start.linkTo(deviceIcon.end, 6.dp)
                                    centerVerticallyTo(parent)
                                },
                                text = itemName,
                                fontSize = 17.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                },
                onClick = {
                    onClickItem(item)
                },
            )
        }
    }
}