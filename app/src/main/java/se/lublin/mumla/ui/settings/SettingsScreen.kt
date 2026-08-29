/*
 * Copyright (C) 2026 Mumla Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.lublin.mumla.ui.components.VuMeter
import se.lublin.mumla.ui.theme.MumlaTealPrimary
import se.lublin.mumla.ui.theme.OledBlack
import se.lublin.mumla.ui.theme.OledBorder
import se.lublin.mumla.ui.theme.OledCardSurface

@Composable
fun SettingsScreen(
    versionName: String = "Mumla OLED",
    modifier: Modifier = Modifier
) {
    var inputMethod by remember { mutableStateOf("ptt") } // "ptt", "voiceActivity", "continuous"
    var vadThreshold by remember { mutableFloatStateOf(0.5f) }
    var oledDarkEnabled by remember { mutableStateOf(true) }
    var hotCornerEnabled by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OledBlack)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "Settings",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Section: Audio & Transmission
        SettingsSectionHeader(title = "Audio & Voice Transmission", icon = Icons.Default.Mic)

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = OledCardSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, OledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Input Method",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inputMethod = "ptt" }
                ) {
                    RadioButton(
                        selected = inputMethod == "ptt",
                        onClick = { inputMethod = "ptt" },
                        colors = RadioButtonDefaults.colors(selectedColor = MumlaTealPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Push To Talk (PTT)", color = Color.White, fontSize = 14.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inputMethod = "voiceActivity" }
                ) {
                    RadioButton(
                        selected = inputMethod == "voiceActivity",
                        onClick = { inputMethod = "voiceActivity" },
                        colors = RadioButtonDefaults.colors(selectedColor = MumlaTealPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Voice Activity (VAD)", color = Color.White, fontSize = 14.sp)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { inputMethod = "continuous" }
                ) {
                    RadioButton(
                        selected = inputMethod == "continuous",
                        onClick = { inputMethod = "continuous" },
                        colors = RadioButtonDefaults.colors(selectedColor = MumlaTealPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continuous Transmission", color = Color.White, fontSize = 14.sp)
                }

                if (inputMethod == "voiceActivity") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "VAD Activation Threshold",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    VuMeter(
                        currentLevel = 0.35f,
                        threshold = vadThreshold
                    )

                    Slider(
                        value = vadThreshold,
                        onValueChange = { vadThreshold = it },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = MumlaTealPrimary,
                            activeTrackColor = MumlaTealPrimary,
                            inactiveTrackColor = Color(0xFF333333)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section: OLED & Appearance
        SettingsSectionHeader(title = "Appearance & OLED Optimization", icon = Icons.Default.DarkMode)

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = OledCardSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, OledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "True OLED Black Theme",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Pure #000000 background for lowest power consumption",
                            color = Color(0xFF888888),
                            fontSize = 12.sp
                        )
                    }

                    Switch(
                        checked = oledDarkEnabled,
                        onCheckedChange = { oledDarkEnabled = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = MumlaTealPrimary
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section: About
        SettingsSectionHeader(title = "About", icon = Icons.Default.Info)

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = OledCardSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, OledBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Mumla OLED",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Version: $versionName",
                    color = Color(0xFFAAAAAA),
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "GPL-3.0-or-later • High-Performance Mumble Voice Client with Jetpack Compose & Material 3",
                    color = Color(0xFF777777),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MumlaTealPrimary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            color = MumlaTealPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}
